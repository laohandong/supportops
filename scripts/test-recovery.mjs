import assert from 'node:assert/strict';
import {readFile,mkdir} from 'node:fs/promises';
import {createWriteStream} from 'node:fs';
import {resolve,join} from 'node:path';
import {pathToFileURL} from 'node:url';
import {randomUUID} from 'node:crypto';
import {options,privateDirectory,atomicJson,digest,execute} from './reliability/common.mjs';
import {prepareRecovery} from './reliability/prepare-recovery.mjs';
import {verifyDeployment,cleanDeployment} from './reliability/deployment.mjs';
import {backupPlan,makeBackup,restoreBackup,stoppedWriters,validateBackup} from './reliability/backup-v2.mjs';
import {minio,objectList,es} from './reliability/storage.mjs';
import {acceptRestore} from './reliability/restore-acceptance.mjs';
import {busyWriters,storageState,invalidBackups,containment,interruptedBackup,interruptedRestore,dependencyTimeout,privacy} from './reliability/recovery-checks.mjs';
import {launch} from './reliability/runtime.mjs';
import {loadLocalConfig} from './local-config.mjs';
import {systemEnvironment} from './lib/runtime-safety.mjs';
import {validateExternalDependencies} from './reliability/external-dependencies.mjs';

/** 所有 B01—B07 都使用真实隔离存储，失败保留原始证据，不重写成通过。 */
export async function runRecovery({source,prepared:preparedPath,directory,jar,keep=false,root=process.cwd()}={}){
  if(!source)throw new Error('SOURCE_REQUIRED');
  directory=resolve(directory||join(root,'.local/reliability-recovery',randomUUID()));
  source=resolve(source);jar=resolve(jar||join(root,'target/supportops-0.1.0-SNAPSHOT.jar'));
  await privateDirectory(directory);await mkdir(join(directory,'public'));
  const report={schemaVersion:1,platform:process.platform,node:process.version,jarSha256:await digest(jar),status:'RUNNING',cases:[],cleanup:[]};
  const commandEnvironment={...systemEnvironment(),HOMEDRIVE:process.env.HOMEDRIVE,HOMEPATH:process.env.HOMEPATH,ProgramData:process.env.ProgramData,ProgramFiles:process.env.ProgramFiles,
    SUPPORTOPS_RD_COMPOSE_ROOT:process.env.SUPPORTOPS_RD_COMPOSE_ROOT, SUPPORTOPS_RD_SUBNET_POOL:process.env.SUPPORTOPS_RD_SUBNET_POOL};
  const cli=async(script,args)=>JSON.parse((await execute(process.execPath,[join(root,script),...args],{env:commandEnvironment,cwd:root,windowsHide:true,timeout:360000,maxBuffer:4*1024*1024})).stdout);
  let prepared,restored,backup,baseline,partial;
  const run=async(id,task)=>{try{report.cases.push({id,status:'PASS',proof:await task()});}catch(error){report.cases.push({id,status:'FAIL',errorCode:/^[A-Z_]+$/.test(error.message)?error.message:'ASSERTION_FAILED'});await atomicJson(join(directory,id+'-failure-private.json'),{message:error.message,stack:error.stack});}await atomicJson(join(directory,'public/recovery-report.json'),report);};
  try{
    prepared=preparedPath?JSON.parse(await readFile(resolve(preparedPath),'utf8')):await prepareRecovery(source,directory,jar,root);
    // 开发复验可读取已停写合成源；运行入口不接受此参数，也不绕过停止证明。
    prepared.deployment=JSON.parse(await readFile(join(source,'deployment/deployment.json'),'utf8'));
    await verifyDeployment(prepared.deployment);await stoppedWriters(prepared.deployment);
    await atomicJson(join(directory,'prepared-private.json'),prepared);
    await run('B01',()=>busyWriters(prepared.deployment,directory,prepared.liveRefusal));
    await run('B02',async()=>{
      const client=minio(prepared.deployment),objects=await objectList(client,prepared.deployment.bucket);assert.ok(objects.length);
      await client.setObjectTagging(prepared.deployment.bucket,objects[0].name,{purpose:'reliability-restore',data:'synthetic'});
      const existing=await es(prepared.deployment,'GET','/_cat/indices?format=json');assert.ok(existing.length);
      await es(prepared.deployment,'POST','/_aliases',{actions:[{add:{index:existing.find(item=>item.index.startsWith('supportops-')).index,alias:'supportops-rd-restoration-alias'}}]});
      baseline=await storageState(prepared.deployment);
      const plan=join(directory,'backup-plan.json');
      const planned=await cli('scripts/backup.mjs',['--deployment='+join(prepared.deployment.directory,'deployment.json'),'--plan='+plan]);assert.equal(planned.status,'PLANNED_READ_ONLY');
      backup=join(directory,'complete-backup');
      const exported=await cli('scripts/backup.mjs',['--execute','--plan='+plan,'--deployment-id='+prepared.deployment.deploymentId,'--output='+backup]);assert.equal(exported.status,'COMPLETE');
      const manifest=JSON.parse(await readFile(join(backup,'manifest.json'),'utf8'));assert.equal(manifest.status,'COMPLETE');assert.equal(manifest.kind,'SUPPORTOPS_BACKUP_V2');assert.equal(manifest.materials.length,0);assert.ok(manifest.mysql.schemas.supportops.outcome_export_snapshots);
      const dependencies=await validateExternalDependencies(join(backup,'configuration/external-dependencies.json'));assert.equal(dependencies.entries.length,2);assert.ok(dependencies.entries.every(entry=>entry.packaged===false));
      assert.ok(manifest.minio.objects.some(object=>object.tags.purpose==='reliability-restore'));assert.ok(Object.keys(manifest.mysql.schemas.supportops_excel).length);
      assert.ok(Object.values(manifest.elastic.indices).some(index=>Object.hasOwn(index.aliases,'supportops-rd-restoration-alias')));
      await validateBackup(backup);const target=join(directory,'restored-target'),restorePlan=join(directory,'restore-plan.json');
      const restorePlanned=await cli('scripts/restore.mjs',['--backup='+backup,'--target='+target,'--plan='+restorePlan]);assert.equal(restorePlanned.status,'PLANNED_READ_ONLY');
      const restoredSummary=await cli('scripts/restore.mjs',['--execute','--plan='+restorePlan,'--target-id='+restorePlanned.targetId]);assert.equal(restoredSummary.status,'RESTORED_STOPPED');
      restored={manifest,deployment:JSON.parse(await readFile(join(target,'deployment.json'),'utf8')),progress:JSON.parse(await readFile(join(target,'restore.json'),'utf8'))};
      assert.deepEqual(await storageState(prepared.deployment),baseline);
      await atomicJson(join(directory,'restore-storage-private.json'),{before:baseline,restored:await storageState(restored.deployment),manifestSha256:await digest(join(backup,'manifest.json'))});
      return {backupStatus:manifest.status,targetStatus:restored.progress.status,mainTables:Object.keys(manifest.mysql.schemas.supportops).length,
        excelTables:Object.keys(manifest.mysql.schemas.supportops_excel).length,objects:manifest.minio.objects.length,indices:Object.keys(manifest.elastic.indices).length,
        evaluationArchives:manifest.materials.length,externalDependenciesRecorded:dependencies.entries.length,externalWeightsPackaged:false,allRowsStructuresObjectsMappingsAliasesEqual:true,businessAcceptance:'B06',sourceUnchanged:true};
    });
    const requireBackup=()=>{if(!restored||!backup)throw new Error('DEPENDENT_COMPLETE_BACKUP_MISSING');};
    await run('B03',async()=>{requireBackup();return {refusals:await invalidBackups(root,backup,directory),anyInvalidTargetCreated:false};});
    await run('B04',async()=>{requireBackup();return containment(root,backup,directory,prepared.deployment);});
    await run('B06',async()=>{requireBackup();return acceptRestore(root,restored,prepared,directory);});
    await run('B05',async()=>{
      requireBackup();const imported=await interruptedRestore(root,backup,directory);partial=imported.deployment;
      const crash=await interruptedBackup(root,prepared.deployment,directory);
      const env=await loadLocalConfig(prepared.deployment.config,systemEnvironment());
      await assert.rejects(launch({root,env,jar,runtimeRoot:prepared.deployment.runtimeRoot,cwd:prepared.deployment.directory,settings:prepared.deployment.settings,port:0,
        deployment:join(prepared.deployment.directory,'deployment.json'),log:createWriteStream(join(directory,'locked-start.log'))}),/MAINTENANCE_LOCKED/);
      restored.deployment=JSON.parse(await readFile(join(restored.deployment.directory,'deployment.json'),'utf8'));
      const timeout=await dependencyTimeout(restored.deployment,directory);
      assert.deepEqual(await storageState(prepared.deployment),baseline);
      return {restore:imported.proof,backup:crash,timeout,startUnderMaintenance:'REFUSED',sourceUnchanged:true};
    });
    await run('B07',async()=>{requireBackup();return privacy(directory,backup,prepared.data);});
    if(report.cases.find(item=>item.id==='B06')?.status!=='PASS'&&report.cases.find(item=>item.id==='B02')?.status==='PASS'){
      report.cases.find(item=>item.id==='B02').status='FAIL';report.cases.find(item=>item.id==='B02').errorCode='RESTORED_BUSINESS_ACCEPTANCE_FAILED';
    }
  }catch(error){report.status='FAIL';report.errorCode=/^[A-Z_]+$/.test(error.message)?error.message:'RECOVERY_PREPARATION_FAILED';await atomicJson(join(directory,'runner-failure-private.json'),{message:error.message,stack:error.stack});}
  finally{
    if(keep&&restored&&report.cases.length===7&&report.cases.every(item=>item.status==='PASS')){
      try{
        const acceptance=await restoreBackup(root,backup,join(directory,'acceptance-target'));
        report.cleanup.push({deploymentId:acceptance.deployment.deploymentId,state:'ACCEPTANCE_RESTORED_STOPPED_STORAGE_RETAINED',directory:'acceptance-target'});
      }catch(error){report.errorCode='ACCEPTANCE_TARGET_CREATE_FAILED';await atomicJson(join(directory,'acceptance-target-failure-private.json'),{message:error.message,stack:error.stack});}
    }
    for(const target of [partial,...(!keep&&restored?[restored.deployment]:[])])if(target){
      try{if(target.processes.length)await stoppedWriters(target);await cleanDeployment(target);report.cleanup.push({deploymentId:target.deploymentId,state:'CLEANED_OWNED_CONTAINERS_AND_VOLUMES'});}
      catch{report.cleanup.push({deploymentId:target.deploymentId,state:'UNKNOWN_RETAINED'});}
    }
    if(keep&&restored)report.cleanup.push({deploymentId:restored.deployment.deploymentId,state:'RESTORED_APPLICATION_STOPPED_STORAGE_RETAINED',directory:'restored-target'});
    if(prepared)report.cleanup.push({deploymentId:prepared.deployment.deploymentId,state:'SOURCE_APPLICATION_STOPPED_STORAGE_RETAINED',maintenance:'B05 evidence'});
    report.cases.sort((left,right)=>left.id.localeCompare(right.id));
    for(const id of ['B01','B02','B03','B04','B05','B06','B07'])if(!report.cases.some(item=>item.id===id))report.cases.push({id,status:'NOT_RUN',errorCode:'PREPARATION_OR_DEPENDENCY_FAILED'});
    report.status=report.cases.every(item=>item.status==='PASS')&&!report.errorCode&&!report.cleanup.some(item=>item.state==='UNKNOWN_RETAINED')?'PASS':'FAIL';await atomicJson(join(directory,'public/recovery-report.json'),report);
  }
  return {directory,report,restored};
}
if(process.argv[1]&&import.meta.url===pathToFileURL(resolve(process.argv[1])).href){
  try{const input=options(process.argv.slice(2),['source','prepared','directory','jar'],['keep']);const result=await runRecovery(input);console.log('Recovery '+result.report.status);process.exitCode=result.report.status==='PASS'?0:1;}
  catch{console.error('RECOVERY_TEST_FAILED');process.exitCode=1;}
}
