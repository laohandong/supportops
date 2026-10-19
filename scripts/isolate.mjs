import {readFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {fileURLToPath,pathToFileURL} from 'node:url';
import {options} from './reliability/common.mjs';
import {createDeployment,verifyDeployment,cleanDeployment} from './reliability/deployment.mjs';
import {stoppedWriters} from './reliability/backup-v1.mjs';

const root=resolve(fileURLToPath(new URL('..',import.meta.url)));
/** 显式创建或清理已登记的专属依赖，默认查询只验证身份。 */
export async function isolate(args=process.argv.slice(2)){
  try{
    const input=options(args,['directory','deployment','deployment-id'],['create','cleanup','fixed-test-ports']);
    if(input.create){
      if(!input.directory||input.deployment||input.cleanup||input['deployment-id'])throw new Error('INPUT_INVALID');
      const deployment=await createDeployment(root,resolve(input.directory),input['fixed-test-ports']?{ports:{mysql:23306,minio:29000,elasticsearch:29200}}:{});
      console.log(JSON.stringify({schemaVersion:1,status:'READY',deploymentId:deployment.deploymentId,directory:deployment.directory,ports:deployment.ports}));
    }else{
      if(!input.deployment||input.directory||input['fixed-test-ports'])throw new Error('INPUT_INVALID');
      const deployment=JSON.parse(await readFile(resolve(input.deployment),'utf8'));await verifyDeployment(deployment);
      if(input.cleanup){
        if(input['deployment-id']!==deployment.deploymentId)throw new Error('DEPLOYMENT_ID_MISMATCH');
        if(deployment.processes.length)await stoppedWriters(deployment);
        await cleanDeployment(deployment);
      }else if(input['deployment-id'])throw new Error('INPUT_INVALID');
      console.log(JSON.stringify({schemaVersion:1,status:deployment.state,deploymentId:deployment.deploymentId}));
    }
    return 0;
  }catch(error){const code=/^[A-Z_]+$/.test(error.message)?error.message:'ISOLATION_FAILED';console.error(JSON.stringify({schemaVersion:1,status:'FAILED',code}));return code==='INPUT_INVALID'?2:1;}
}
if(process.argv[1]&&import.meta.url===pathToFileURL(resolve(process.argv[1])).href)process.exitCode=await isolate();
