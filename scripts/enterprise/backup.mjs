import {mkdir,readFile,writeFile,chmod} from 'node:fs/promises';
import {resolve,join} from 'node:path';
import {parse,deployment,stopped,helper,volumes,hash,writePrivate} from './runtime.mjs';
const options=parse(process.argv.slice(2));
try{
 if(Object.keys(options).some(value=>!['directory','output'].includes(value)))throw new Error('INPUT_INVALID');
 const state=await deployment(options.directory),output=resolve(options.output);
 // 先核对全部资源归属，再停止本部署；不停止、删除或移动任何其他容器。
 await state.compose(['stop','--timeout','30']);const containers=await stopped(state);
 await mkdir(output,{mode:0o700});const files={};
 for(const logical of volumes.filter(value=>value!=='application-data')){
  const file=`${logical}.tar.gz`;
  await helper(state,[`type=volume,source=${state.volumeIds[logical].name},target=/data,readonly`,`type=bind,source=${output},target=/backup`],
   'tar',['-czf',`/backup/${file}`,'--exclude=./mysql.sock','-C','/data','.']);await chmod(join(output,file),0o600);files[file]=await hash(join(output,file));
 }
 // 只采集加密引用设置，不遍历本地文件夹、主密钥、setup Secret、TLS或明文导入源。
 const script=`const fs=require('fs');const file='/data/model-settings.json';if(fs.existsSync(file)){const text=fs.readFileSync(file,'utf8');const value=JSON.parse(text);if(value.format!==2||JSON.stringify(value).length>65536||Object.values(value.roles||{}).some(role=>role['api-key']))throw Error('MODEL_SETTINGS_NOT_PROTECTED');fs.writeFileSync('/backup/model-settings.json',text,{flag:'wx',mode:384});}`;
 await helper(state,[`type=volume,source=${state.volumeIds['application-data'].name},target=/data,readonly`,`type=bind,source=${output},target=/backup`],'node',['-e',script]);
 try{files['model-settings.json']=await hash(join(output,'model-settings.json'));}catch(error){if(error.code!=='ENOENT')throw error;}
 for(const name of ['mysql-root','spring.datasource.password','supportops.knowledge.excel.query-password','supportops.knowledge.storage.secret-key']){
  const file=`${name}.secret`;await writePrivate(join(output,file),await readFile(join(state.directory,'secrets',name)));files[file]=await hash(join(output,file));
 }
 const metadata={kind:'ENTERPRISE_COLD_BACKUP_V1',complete:true,sourceDeploymentId:state.metadata.deploymentId,
  images:state.metadata.images,files,volumes:state.volumeIds,stoppedContainers:containers.map(value=>({id:value.Id,service:value.Config.Labels['com.docker.compose.service'],finishedAt:value.State.FinishedAt,exitCode:value.State.ExitCode})),
  masterKeyIncluded:false,setupSecretIncluded:false,tlsIncluded:false,createdAt:new Date().toISOString(),capacity:'COLD_DEDICATED_VOLUMES_FIXED_IMAGE_V1'};
 await writePrivate(join(output,'manifest.json'),JSON.stringify(metadata,null,2)+'\n');
 console.log(JSON.stringify({status:'BACKUP_COMPLETE_STOPPED',deploymentId:metadata.sourceDeploymentId,manifestSha256:await hash(join(output,'manifest.json')),files:Object.keys(files).length}));
}catch(error){console.error(JSON.stringify({status:'FAILED_RETAINED',code:/^[A-Z_]+$/.test(error.message)?error.message:'ENTERPRISE_BACKUP_FAILED'}));process.exitCode=1;}
