import assert from 'node:assert/strict';
import {readFile,writeFile,mkdir,cp,stat,access,unlink,link,symlink} from 'node:fs/promises';
import {join,resolve} from 'node:path';
import {spawn} from 'node:child_process';
import {randomUUID} from 'node:crypto';
import {privateDirectory,atomicJson,digest,hash,files,safeFile,until,verifyPrivateDirectory} from './common.mjs';
import {backupPlan,makeBackup,restoreBackup,validateBackup,stoppedWriters,captureObjects} from './backup-v2.mjs';
import {connection,closeDatabase,query,databaseSnapshot,elasticSnapshot,minio,objectList,es} from './storage.mjs';
import {docker,verifyDeployment} from './deployment.mjs';

export const absent=async path=>{try{await access(path);return false;}catch(error){if(error.code==='ENOENT')return true;throw error;}};
export async function storageState(deployment){return {mysql:await databaseSnapshot(deployment),minio:await captureObjects(deployment),elastic:await elasticSnapshot(deployment)};}
export async function rows(deployment,sql,params=[]){const database=await connection(deployment);try{return await query(database,sql,params);}finally{await closeDatabase(database);}}
export async function cloneBackup(source,target){await privateDirectory(target);await cp(source,target,{recursive:true,force:false});return target;}

/** 故障点只更改真实合成数据的一行，finally 按原值恢复；不停止未知进程。 */
export async function busyWriters(deployment,directory,liveRefusal){
  assert.equal(liveRefusal,'APPLICATION_NOT_CONFIRMED_STOPPED');
  const before=await databaseSnapshot(deployment),refusals=[liveRefusal];
  const tests=[
    {select:'SELECT id,status FROM supportops.runs LIMIT 1',change:"UPDATE supportops.runs SET status='QUEUED' WHERE id=?",undo:'UPDATE supportops.runs SET status=? WHERE id=?',expected:'BACKGROUND_WRITER_ACTIVE',values:r=>[r.status,r.id]},
    
    {select:'SELECT id,status,process_id AS pid,process_started_at AS started FROM supportops.remediation_executions LIMIT 1',change:"UPDATE supportops.remediation_executions SET status='UNKNOWN',process_id=?,process_started_at=NULL WHERE id=?",undo:'UPDATE supportops.remediation_executions SET status=?,process_id=?,process_started_at=? WHERE id=?',expected:'EXECUTION_IDENTITY_UNKNOWN_OR_ACTIVE',values:r=>[r.status,r.pid,r.started,r.id],process:true}
  ];
  for(const test of tests){
    const original=(await rows(deployment,test.select))[0];assert.ok(original);
    try{
      await rows(deployment,test.change,test.process?[process.pid,original.id]:[original.id]);
      await assert.rejects(backupPlan(deployment,join(directory,randomUUID()+'.json')),error=>{assert.equal(error.message,test.expected);return true;});
      refusals.push(test.expected);
    }finally{await rows(deployment,test.undo,test.values(original));}
  }
  // 空闲连接也不能视为已停写；以真实连接证明关闭等待不会放宽数据库写入者检查。
  const held = await connection(deployment), heldId = held.threadId;
  try {
    await query(held, 'SELECT CONNECTION_ID()');
    await assert.rejects(backupPlan(deployment,join(directory,'idle-connection-must-not-create.json')), /UNKNOWN_DATABASE_WRITER/);
    refusals.push('UNKNOWN_DATABASE_WRITER');
  } finally { await closeDatabase(held); }
  const remaining = await rows(deployment,'SELECT ID FROM information_schema.PROCESSLIST WHERE ID=?',[heldId]);
  assert.equal(remaining.length,0);
  assert.deepEqual(await databaseSnapshot(deployment),before);
  await stoppedWriters(deployment);
  return {refusals,syntheticRowMutationsReverted:true,unknownProcessStopped:false,completeBackupCreated:false,realIdleConnectionRefused:true,closedConnectionAbsent:true};
}

/** 缺失、损坏和不受支持的备份逐个在目标创建前拒绝。 */
export async function invalidBackups(root,backup,directory){
  const cases=[
    ['missing-file',async p=>unlink(join(p,'databases.sql')),'BACKUP_FILE_SET_MISMATCH'],
    ['bad-checksum',async p=>writeFile(join(p,'databases.sql'),'synthetic-corrupt-sql'),'BACKUP_CHECKSUM_INVALID'],
    ['partial',async(p,m)=>{m.status='PARTIAL';await atomicJson(join(p,'manifest.json'),m);},'BACKUP_MANIFEST_UNSUPPORTED'],
    ['schema-version',async(p,m)=>{m.schemaVersion=999;await atomicJson(join(p,'manifest.json'),m);},'BACKUP_MANIFEST_UNSUPPORTED'],
    ['snapshot',async(p,m)=>{m.esRepository.snapshot='failed-snapshot';await atomicJson(join(p,'manifest.json'),m);},'BACKUP_SNAPSHOT_INVALID'],
    ['image-version',async(p,m)=>{m.images.mysql.id='sha256:'+ '0'.repeat(64);await atomicJson(join(p,'manifest.json'),m);},'RESTORE_IMAGE_VERSION_UNSUPPORTED'],
    ['material-path',async(p,m)=>{m.materials=[{attemptId:randomUUID(),originalPath:p,archive:'../escape'}];await atomicJson(join(p,'manifest.json'),m);},'BACKUP_MATERIAL_INVALID']
  ];
  const evidence=[];
  for(const[name,mutate,expected]of cases){
    const damaged=await cloneBackup(backup,join(directory,'invalid-'+name)),target=join(directory,'must-not-exist-'+name);
    const manifest=JSON.parse(await readFile(join(damaged,'manifest.json'),'utf8'));await mutate(damaged,manifest);
    await assert.rejects(restoreBackup(root,damaged,target),error=>{assert.equal(error.message,expected);return true;});
    assert.equal(await absent(target),true);evidence.push({name,errorCode:expected,targetCreated:false});
  }
  return evidence;
}

/** 目标路径、部署身份和归档链接均不能复用源或逃逸专属目录。 */
export async function containment(root,backup,directory,source){
  const sourceBefore=await storageState(source),manifest=await validateBackup(backup),refusals=[];
  await assert.rejects(restoreBackup(root,backup,source.directory),/RESTORE_SOURCE_REUSE/);refusals.push('RESTORE_SOURCE_REUSE');
  const existing=join(directory,'existing-target');await mkdir(existing);await writeFile(join(existing,'sentinel.txt'),'retain-me');
  await assert.rejects(restoreBackup(root,backup,existing),/RESTORE_TARGET_NOT_EMPTY/);assert.equal(await readFile(join(existing,'sentinel.txt'),'utf8'),'retain-me');refusals.push('RESTORE_TARGET_NOT_EMPTY');
  const reused=join(directory,'source-volume-reuse');
  await assert.rejects(restoreBackup(root,backup,reused,{targetId:source.deploymentId}),/RESTORE_SOURCE_RESOURCE_REUSE/);assert.equal(await absent(reused),true);refusals.push('RESTORE_SOURCE_RESOURCE_REUSE');
  const ancestor=join(directory,'linked-ancestor');await symlink(source.directory,ancestor,process.platform==='win32'?'junction':'dir');
  try{await assert.rejects(restoreBackup(root,backup,join(ancestor,'escape')),/BACKUP_LINK_INVALID/);assert.equal(await absent(join(source.directory,'escape')),true);}finally{await unlink(ancestor);}
  refusals.push('ANCESTOR_LINK_REJECTED');
  const hard=await cloneBackup(backup,join(directory,'hardlink-backup')),hardPath=join(hard,'databases.sql');
  await unlink(hardPath);await link(join(backup,'databases.sql'),hardPath);
  try{await assert.rejects(validateBackup(hard),/BACKUP_LINK_INVALID/);}finally{await unlink(hardPath);}
  assert.equal((await stat(join(backup,'databases.sql'))).nlink,1);refusals.push('HARDLINK_REJECTED');
  await assert.rejects(safeFile(backup,'../databases.sql'),/BACKUP_PATH_INVALID/);
  const escaped=await cloneBackup(backup,join(directory,'escape-backup'));manifest.files['../outside.sql']={size:0,sha256:hash('')};await atomicJson(join(escaped,'manifest.json'),manifest);
  await assert.rejects(restoreBackup(root,escaped,join(directory,'escape-target')),/BACKUP_FILE_SET_MISMATCH/);assert.equal(await absent(join(directory,'escape-target')),true);refusals.push('ARCHIVE_ESCAPE_REJECTED');
  const wrong={...source,resources:{...source.resources,mysql:{...source.resources.mysql,volumes:['unowned-volume']}}};
  await assert.rejects(verifyDeployment(wrong),/DEPLOYMENT_OWNERSHIP_MISMATCH/);refusals.push('UNOWNED_VOLUME_REJECTED');
  assert.deepEqual(await storageState(source),sourceBefore);
  return {refusals,sourceUnchanged:true,existingSentinelUnchanged:true};
}

/** 实际 SQL 导出之后杀掉本次子进程；半成品与维护锁必须保留。 */
export async function interruptedBackup(root,source,directory){
  const planPath=join(directory,'crash-plan.json');await backupPlan(source,planPath);
  const output=join(directory,'interrupted-backup'),signal=join(directory,'export-boundary.txt');
  const child=spawn(process.execPath,[join(root,'src/test/recovery/backup-crash.mjs'),planPath,source.deploymentId,output,signal],{cwd:root,env:process.env,windowsHide:true,stdio:['ignore','pipe','pipe']});
  const outputParts=[];child.stdout.on('data',x=>outputParts.push(x));child.stderr.on('data',x=>outputParts.push(x));
  const ended=new Promise(done=>child.once('close',(code,termination)=>done({code,termination})));ended.catch(()=>{});
  try{
    await until(async()=>!await absent(signal),60000,'EXPORT_BOUNDARY_TIMEOUT');
    assert.ok((await stat(join(output,'databases.sql'))).size>0);
    child.kill('SIGKILL');const exit=await ended;
    const manifest=JSON.parse(await readFile(join(output,'manifest.json'),'utf8')),lock=JSON.parse(await readFile(join(source.directory,'maintenance.lock'),'utf8'));
    assert.equal(manifest.status,'WRITING');assert.equal(lock.state,'HELD');assert.equal(lock.pid,child.pid);
    await assert.rejects(validateBackup(output),/BACKUP_MANIFEST_UNSUPPORTED/);
    await atomicJson(join(directory,'crash-child-private.json'),{exit,lock,manifest,output:Buffer.concat(outputParts).toString()});
    return {actualSqlExported:true,childKilledByOwnHandle:true,manifest:'WRITING',maintenanceLock:'HELD',cleanup:'STOPPED_CHILD_LOCK_RETAINED'};
  }finally{if(child.exitCode===null&&child.signalCode===null){child.kill('SIGKILL');await ended;}}
}

/** 真实 MySQL 导入后制造中断，目标保留 PARTIAL，禁止原目标盲目追加。 */
export async function interruptedRestore(root,backup,directory){
  const target=join(directory,'partial-target');
  await assert.rejects(restoreBackup(root,backup,target,{fault:async stage=>{if(stage==='MYSQL_IMPORTED')throw new Error('SYNTHETIC_IMPORT_INTERRUPTION');}}),/SYNTHETIC_IMPORT_INTERRUPTION/);
  const progress=JSON.parse(await readFile(join(target,'restore.json'),'utf8')),deployment=JSON.parse(await readFile(join(target,'deployment.json'),'utf8')),manifest=await validateBackup(backup);
  assert.equal(progress.status,'PARTIAL');assert.equal(progress.stage,'MYSQL');assert.deepEqual(await databaseSnapshot(deployment),manifest.mysql);
  assert.equal((await minio(deployment).listBuckets()).length,0);assert.equal(Object.keys((await elasticSnapshot(deployment)).indices).length,0);
  await assert.rejects(restoreBackup(root,backup,target),/RESTORE_TARGET_NOT_EMPTY/);
  return {deployment,proof:{actualMysqlImported:true,status:progress.status,stage:progress.stage,sameTargetRetry:'REFUSED',fault:'TEST_CALLBACK_AFTER_REAL_IMPORT'}};
}

/** 暂停本轮已登记的 ES 造成真实超时；finally 恢复该依赖，不操作其他容器。 */
export async function dependencyTimeout(deployment,directory){
  const plan=join(directory,'timeout-plan.json');await backupPlan(deployment,plan);
  const output=join(directory,'timeout-backup'),started=Date.now();
  await verifyDeployment(deployment);await docker(deployment,['pause',deployment.resources.elasticsearch.id]);
  try{await assert.rejects(makeBackup(plan,deployment.deploymentId,output));}
  finally{await docker(deployment,['unpause',deployment.resources.elasticsearch.id]);}
  const manifest=JSON.parse(await readFile(join(output,'manifest.json'),'utf8'));
  assert.equal(manifest.status,'FAILED');assert.equal(manifest.maintenanceLock,'RETAINED');assert.ok((await stat(join(output,'databases.sql'))).size>0);
  await assert.rejects(validateBackup(output),/BACKUP_MANIFEST_UNSUPPORTED/);
  return {actualEsPaused:true,durationMs:Date.now()-started,manifest:'FAILED',maintenanceLock:'RETAINED',dependency:'UNPAUSED_OWN_CONTAINER'};
}

/** 私有 ACL/mode 与可分享白名单分别验证，公开报告只包含脱敏业务证明。 */
export async function privacy(directory,backup,data){
  const protection=await verifyPrivateDirectory(backup);await verifyPrivateDirectory(directory);
  const names=await files(join(directory,'public'));
  const secrets=[data.accounts.admin.password,'synthetic-rd-fixture','synthetic-rd-recovery-fixture','synthetic-knowledge-root','synthetic-knowledge-minio'];
  for(const name of names){
    assert.ok(/\.(json|png|md)$/.test(name));assert.ok(!/private|\.env|\.sql|\.jar|trace|\.har|runtime\.json/.test(name));
    if(name.endsWith('.png'))continue;
    const text=await readFile(join(directory,'public',name),'utf8');for(const secret of secrets)assert.ok(!text.includes(secret));assert.ok(!/set-cookie|session-token|Bearer\s+[A-Za-z0-9]/i.test(text));
  }
  return {protection,publicFiles:names,credentialsPrinted:false,cookiesTransferred:false,sourceConfiguration:'PRIVATE_ARCHIVE_ONLY'};
}
