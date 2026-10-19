import {readFile,copyFile,mkdir,stat,chmod,chown} from 'node:fs/promises';
import {resolve,join} from 'node:path';
import {randomUUID} from 'node:crypto';
import {parse,deployment,run,writePrivate,hash} from './runtime.mjs';
const input=parse(process.argv.slice(2));
try {
 if(Object.keys(input).some(key=>!['directory','action','request-id','holder-id','holder-container','source-file','target-file'].includes(key)))throw new Error('INPUT_INVALID');
 if(!['rotate','import-model','recover-guard'].includes(input.action))throw new Error('ACTION_INVALID');
 if(!/^[a-f0-9-]{36}$/.test(input['request-id']||''))throw new Error('FIXED_REQUEST_ID_REQUIRED');
 const state=await deployment(input.directory),app=state.containers.find(value=>value.Config.Labels['com.docker.compose.service']==='app');
 const active=(await run(['ps','--quiet','--filter',`label=io.supportops.deployment=${state.metadata.deploymentId}`,'--filter','label=com.docker.compose.service=app'])).stdout.trim();
 if(active||app.State.Running)throw new Error('APPLICATION_MUST_BE_STOPPED');
 const work=join(state.directory,`maintenance-${input['request-id']}`);
 const frozen={deploymentId:state.metadata.deploymentId,action:input.action,holderId:input['holder-id']||null,
  holderContainer:input['holder-container']||null,sourceFile:input['source-file']?resolve(input['source-file']):null,
  sourceSha256:input['source-file']?await hash(resolve(input['source-file'])):null,targetFile:input['target-file']||null};
 const fresh=await mkdir(work,{mode:0o700}).then(()=>true).catch(error=>{if(error.code==='EEXIST')return false;throw error;});
 if(fresh)await writePrivate(join(work,'command.json'),JSON.stringify(frozen));
 else if(JSON.stringify(JSON.parse(await readFile(join(work,'command.json'),'utf8')))!==JSON.stringify(frozen))throw new Error('FIXED_COMMAND_CONFLICT');
 const existing=await readFile(join(work,'receipt.json'),'utf8').catch(error=>{if(error.code==='ENOENT')return null;throw error;});
 if(existing){const receipt=JSON.parse(existing);if(receipt.status!=='COMPLETED')throw new Error('MAINTENANCE_RECEIPT_INVALID');console.log(JSON.stringify({...receipt,replayed:true}));process.exit(0);}
 const extra=[];
 if(input.action==='recover-guard'){
  if(!/^[a-f0-9-]{36}$/.test(input['holder-id']||''))throw new Error('HOLDER_ID_REQUIRED');
  const holder=JSON.parse((await run(['inspect',input['holder-container']||app.Id])).stdout)[0];
  if(holder.Config.Labels?.['io.supportops.deployment']!==state.metadata.deploymentId||holder.Image!==state.metadata.images.app.id
   ||holder.State.Running||holder.State.Paused||holder.State.FinishedAt.startsWith('0001-'))throw new Error('STOPPED_HOLDER_PROOF_REQUIRED');
  if(fresh)await writePrivate(join(work,'stopped-proof.json'),JSON.stringify({kind:'STOPPED_COMPOSE_HOLDER_V1',deploymentId:state.metadata.deploymentId,
   holderRuntimeId:input['holder-id'],containerId:holder.Id,containerHost:holder.Config.Hostname,jarSha256:state.metadata.jarSha256,
   stopped:true,finishedAt:holder.State.FinishedAt,verifiedAt:new Date().toISOString()}));
  extra.push(`--supportops.ops.holder-id=${input['holder-id']}`,'--supportops.ops.stopped-proof=/maintenance/stopped-proof.json');
 }
 if(input.action==='import-model'){
  const source=resolve(input['source-file']),target=input['target-file'];
  if(!/^[a-z][a-z0-9-]{0,63}\.json$/.test(target||'')||target==='model-settings.json')throw new Error('NEW_IMPORT_TARGET_REQUIRED');
  const details=await stat(source);if(!details.isFile()||details.size>65536)throw new Error('IMPORT_SOURCE_INVALID');
  const sourceCopy=join(work,'legacy-source.json');
  const copied=await stat(sourceCopy).catch(error=>{if(error.code==='ENOENT')return null;throw error;});
  if(!copied){await copyFile(source,sourceCopy,1);await chmod(sourceCopy,0o600);}
  if(await hash(sourceCopy)!==frozen.sourceSha256)throw new Error('IMPORT_SOURCE_CHANGED');
  extra.push('--supportops.ops.source-file=/maintenance/legacy-source.json',`--supportops.settings.file=/var/lib/supportops/${target}`);
 }
 await state.compose(['up','--detach','--wait','mysql','minio','elasticsearch']);
 // 应用已删除全部Linux能力；运维使用同一非root身份读取10001拥有的只读秘密，不添加绕过目录权限的能力。
 if(process.platform!=='win32'&&process.getuid?.()===0){
  await chown(work,10001,10001);
  for(const file of ['command.json','stopped-proof.json','legacy-source.json']){
   const path=join(work,file),exists=await stat(path).catch(error=>{if(error.code==='ENOENT')return null;throw error;});
   if(exists)await chown(path,10001,10001);
  }
 }
 const result=await state.compose(['run','--rm','--no-deps','--user','10001:10001','--volume',`${work}:/maintenance`,'--entrypoint','java','app',
  '-cp','/opt/supportops/application.jar','-Dloader.main=io.supportops.ops.EnterpriseMaintenance','org.springframework.boot.loader.launch.PropertiesLauncher',
  '--spring.config.additional-location=file:/opt/supportops/enterprise.yml','--spring.flyway.enabled=false',`--supportops.ops.action=${input.action}`,
  '--supportops.ops.receipt-file=/maintenance/receipt.json',...extra]).catch(async error=>{
   await writePrivate(join(work,`failure-${randomUUID()}.log`),(error.stdout||'')+(error.stderr||''));
   throw error;
  });
 const line=result.stdout.split('\n').find(value=>value.startsWith('OPS:'));if(!line)throw new Error('MAINTENANCE_RECEIPT_MISSING');
 const receipt=JSON.parse(line.slice(4));if(receipt.status!=='COMPLETED')throw new Error('MAINTENANCE_FAILED');
 console.log(JSON.stringify({...receipt,receiptFile:join(work,'receipt.json'),application:'STOPPED'}));
}catch(error){console.error(JSON.stringify({status:'FAILED_RETAINED',code:/^[A-Z_]+$/.test(error.message)?error.message:'ENTERPRISE_OPS_FAILED'}));process.exitCode=1;}
