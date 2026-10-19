import {readFile,writeFile,copyFile,chown,chmod,stat,mkdir} from 'node:fs/promises';
import {join,resolve} from 'node:path';
import {randomUUID,randomBytes} from 'node:crypto';
import {parse,deployment,helper,verifyBackup,volumes,run,hash,writePrivate} from './runtime.mjs';
const options=parse(process.argv.slice(2));
try{
 if(Object.keys(options).some(value=>!['directory','backup','source-master-key-file'].includes(value)))throw new Error('INPUT_INVALID');
 const backup=await verifyBackup(options.backup),directory=resolve(options.directory);
 const initial=JSON.parse(await readFile(join(directory,'deployment.json'),'utf8'));
 let sourceRing=null;
 if(options['source-master-key-file']){
  const sourceFile=resolve(options['source-master-key-file']),details=await stat(sourceFile);
  if(!details.isFile()||details.size>16384)throw new Error('SOURCE_MASTER_KEY_INVALID');
  sourceRing=JSON.parse(await readFile(sourceFile,'utf8'));
  if(sourceRing.deploymentId!==backup.metadata.sourceDeploymentId||Object.keys(sourceRing.keys||{}).length>15
   ||Object.entries(sourceRing.keys||{}).some(([key,value])=>! /^[A-Za-z0-9_-]{1,64}$/.test(key)
    ||typeof value!=='string'||Buffer.from(value,'base64').length!==32))throw new Error('SOURCE_MASTER_KEY_INVALID');
 }
 if(initial.kind!=='ENTERPRISE_COMPOSE_V1'||initial.deploymentId===backup.metadata.sourceDeploymentId)throw new Error('FRESH_TARGET_REQUIRED');
 if(await stat(join(directory,'restore-state.json')).catch(error=>{if(error.code==='ENOENT')return null;throw error;}))throw new Error('RESTORE_ALREADY_ATTEMPTED');
 // 镜像匹配和归档路径检查必须发生在任何目标卷写入之前。
 for(const kind of ['mysql','minio','elasticsearch'])if(initial.images[kind]?.id!==backup.metadata.images[kind]?.id)throw new Error('COLD_RESTORE_IMAGE_MISMATCH');
 const composeArgs=['compose','--project-directory',directory,'-f',join(directory,'compose.yml')];
 const ids=(await run([...composeArgs,'ps','--all','--quiet'])).stdout.trim();if(ids)throw new Error('FRESH_TARGET_REQUIRED');
 await run([...composeArgs,'create']);const state=await deployment(directory,{fresh:true});
 for(const logical of volumes){
  const result=await helper(state,[`type=volume,source=${state.volumeIds[logical].name},target=/data,readonly`],
   'sh',['-c','test -z "$(find /data -mindepth 1 -maxdepth 1 -print -quit)"']);
 }
 for(const logical of volumes.filter(value=>value!=='application-data')){
  const archive=`/backup/${logical}.tar.gz`,mount=[`type=bind,source=${backup.directory},target=/backup,readonly`];
  const listing=(await helper(state,mount,'tar',['-tzf',archive])).stdout.trim().split('\n');
  if(listing.length>100000||listing.some(path=>path.startsWith('/')||path.split('/').includes('..')||path.length>4096))throw new Error('ARCHIVE_PATH_INVALID');
  const types=(await helper(state,mount,'tar',['-tvzf',archive])).stdout.trim().split('\n');
  if(types.some(line=>!['-','d'].includes(line[0])))throw new Error('ARCHIVE_SPECIAL_FILE_REJECTED');
 }
 const proof={kind:'FRESH_ENTERPRISE_VOLUMES_V1',sourceDeploymentId:backup.metadata.sourceDeploymentId,targetDeploymentId:initial.deploymentId,
  backupManifestSha256:backup.manifestSha256,applicationNeverStarted:true,freshVolumes:state.volumeIds,verifiedAt:new Date().toISOString()};
 await writePrivate(join(directory,'fresh-restore-proof.json'),JSON.stringify(proof,null,2));
 await writePrivate(join(directory,'restore-state.json'),JSON.stringify({status:'PREPARED',...proof},null,2));
 // 目标都是已经证明为空的新卷，不覆盖源部署或已有工作台。
 for(const logical of volumes.filter(value=>value!=='application-data'))await helper(state,
  [`type=bind,source=${backup.directory},target=/backup,readonly`,`type=volume,source=${state.volumeIds[logical].name},target=/data`],
  'tar',['-xzf',`/backup/${logical}.tar.gz`,'--no-same-permissions','-C','/data']);
 for(const name of ['mysql-root','spring.datasource.password','supportops.knowledge.excel.query-password','supportops.knowledge.storage.secret-key']){
  const file=join(directory,'secrets',name);await copyFile(join(backup.directory,`${name}.secret`),file);await chmod(file,0o600);
  if(process.platform!=='win32'&&process.getuid?.()===0)await chown(file,10001,10001);
  if(name!=='supportops.knowledge.storage.secret-key'){
   const mysqlFile=join(directory,'mysql-secrets',name);await copyFile(file,mysqlFile);await chmod(mysqlFile,0o600);
   if(process.platform!=='win32'&&process.getuid?.()===0)await chown(mysqlFile,999,999);
  }
 }
 // 引用保留，模型路由统一停用；不会将源设置或秘密自动用于外部请求。
 const settings=backup.metadata.files['model-settings.json']?JSON.parse(await readFile(join(backup.directory,'model-settings.json'),'utf8')):{format:2,roles:{}};
 if(settings.format!==2||Object.values(settings.roles||{}).some(role=>role['api-key']))throw new Error('MODEL_SETTINGS_NOT_PROTECTED');
 for(const role of Object.values(settings.roles||{}))role.provider='disabled';
 await writePrivate(join(directory,'restored-model-settings.json'),JSON.stringify(settings,null,2));
 await helper(state,[`type=bind,source=${directory},target=/restore,readonly`,`type=volume,source=${state.volumeIds['application-data'].name},target=/data`],
  'sh',['-c','cp /restore/restored-model-settings.json /data/model-settings.json && chown 10001:10001 /data/model-settings.json && chmod 600 /data/model-settings.json']);
 const ringFile=join(directory,'secrets','master-key.json'),fresh={deploymentId:initial.deploymentId,activeKeyId:`r_${randomUUID().replaceAll('-','')}`,keys:{}};
 fresh.keys[fresh.activeKeyId]=randomBytes(32).toString('base64');
 const combined=structuredClone(fresh);
 if(options['source-master-key-file']){
  Object.assign(combined.keys,sourceRing.keys);
 }
 await writeFile(ringFile,JSON.stringify(combined),{mode:0o600});await chmod(ringFile,0o600);
 if(process.platform!=='win32'&&process.getuid?.()===0)await chown(ringFile,10001,10001);
 await state.compose(['up','--detach','--wait','mysql','minio','elasticsearch']);
 const maintenanceDirectory=join(directory,'restore-maintenance');await mkdir(maintenanceDirectory,{mode:0o700});
 const maintenanceProof=join(maintenanceDirectory,'fresh-restore-proof.json');await copyFile(join(directory,'fresh-restore-proof.json'),maintenanceProof,1);await chmod(maintenanceProof,0o600);
 if(process.platform!=='win32'&&process.getuid?.()===0){await chown(maintenanceDirectory,10001,10001);await chown(maintenanceProof,10001,10001);}
 const maintenance=async(action,extra=[])=>{
  const args=[...composeArgs,'run','--rm','--no-deps','--user','10001:10001','--volume',`${maintenanceDirectory}:/restore`,
   '--entrypoint','java','app','-cp','/opt/supportops/application.jar','-Dloader.main=io.supportops.ops.EnterpriseMaintenance',
   'org.springframework.boot.loader.launch.PropertiesLauncher','--spring.config.additional-location=file:/opt/supportops/enterprise.yml','--spring.flyway.enabled=false',
   `--supportops.ops.action=${action}`,`--supportops.ops.receipt-file=/restore/${action}-receipt.json`,...extra];
  const result=await run(args);const line=result.stdout.split('\n').find(value=>value.startsWith('OPS:'));
  if(!line||JSON.parse(line.slice(4)).status!=='COMPLETED')throw new Error('RESTORE_MAINTENANCE_FAILED');
 };
 await maintenance('reset-restored',[`--supportops.ops.source-deployment-id=${backup.metadata.sourceDeploymentId}`,
  '--supportops.ops.fresh-restore-proof=/restore/fresh-restore-proof.json']);
 if(options['source-master-key-file']){
  await maintenance('rotate');await writeFile(ringFile,JSON.stringify(fresh),{mode:0o600});
 }
 await writeFile(join(directory,'restore-state.json'),JSON.stringify({...proof,status:'RESTORED_APP_STOPPED',credentials:options['source-master-key-file']?'REENCRYPTED':'SOURCE_KEY_UNAVAILABLE',completedAt:new Date().toISOString()},null,2),{mode:0o600});
 console.log(JSON.stringify({status:'RESTORED_APP_STOPPED',deploymentId:initial.deploymentId,credentials:options['source-master-key-file']?'REENCRYPTED':'SOURCE_KEY_UNAVAILABLE',externalCapabilities:'REQUIRES_NEW_APPROVAL'}));
}catch(error){
 if(typeof options.directory==='string'){
  const directory=resolve(options.directory);
  await writePrivate(join(directory,`restore-failure-${randomUUID()}.log`),(error.stack||'')+'\n'+(error.stdout||'')+(error.stderr||'')).catch(()=>{});
 }
 console.error(JSON.stringify({status:'FAILED_RETAINED',code:/^[A-Z_]+$/.test(error.message)?error.message:'ENTERPRISE_RESTORE_FAILED'}));process.exitCode=1;
}
