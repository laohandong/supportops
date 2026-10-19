import {readFile,mkdir,copyFile} from 'node:fs/promises';
import {join,resolve} from 'node:path';
import {pathToFileURL} from 'node:url';
import {randomUUID} from 'node:crypto';
import {options,privateDirectory,atomicJson,digest} from './reliability/common.mjs';
import {runRuntime} from './test-runtime.mjs';
import {runBrowser} from './test-e2e.mjs';
import {runRecovery} from './test-recovery.mjs';
import {cleanDeployment} from './reliability/deployment.mjs';
import {stoppedWriters} from './reliability/backup-v2.mjs';

/** 最终 JAR 的串行交付矩阵；各自独立资源，失败项与清理项单列。 */
export async function runDelivery({directory,deployment,jar,keep=false,root=process.cwd()}={}){
  directory=resolve(directory||join(root,'.local/reliability-delivery',randomUUID()));
  jar=resolve(jar||join(root,'target/supportops-0.1.0-SNAPSHOT.jar'));
  await privateDirectory(directory);await mkdir(join(directory,'public'));
  const report={schemaVersion:1,platform:process.platform,node:process.version,jarSha256:await digest(jar),status:'RUNNING',suites:[],cleanup:[]};
  let browser;
  try{
    const runtime=await runRuntime({deployment,directory:join(directory,'runtime'),jar,root});report.suites.push({id:'D02-D06',status:runtime.status});
    browser=await runBrowser({directory:join(directory,'browser'),jar,root,keep:true});report.suites.push({id:'E01-E08',status:browser.report.status});
    if(browser.report.status==='PASS'){
      const recovery=await runRecovery({source:browser.directory,directory:join(directory,'recovery'),jar,root,keep});report.suites.push({id:'B01-B07',status:recovery.report.status});
    }else report.suites.push({id:'B01-B07',status:'NOT_RUN',reason:'BROWSER_SOURCE_NOT_ACCEPTED'});
  }catch(error){report.errorCode=/^[A-Z_]+$/.test(error.message)?error.message:'DELIVERY_FAILED';}
  finally{
    if(browser&&!keep){
      try{const source=JSON.parse(await readFile(join(browser.directory,'deployment/deployment.json'),'utf8'));await stoppedWriters(source);await cleanDeployment(source);report.cleanup.push({deploymentId:source.deploymentId,status:'CLEANED_OWNED_RESOURCES'});}
      catch{report.cleanup.push({status:'UNKNOWN_RETAINED'});}
    }
    for(const name of ['runtime','browser','recovery']){
      try{const local=JSON.parse(await readFile(join(directory,name,'public',name==='runtime'?'runtime-report.json':name==='browser'?'browser-report.json':'recovery-report.json'),'utf8'));
        await atomicJson(join(directory,'public',name+'-report.json'),local);}catch{}
    }
    report.status=report.suites.length===3&&report.suites.every(item=>item.status==='PASS')&&!report.errorCode&&report.cleanup.every(item=>item.status!=='UNKNOWN_RETAINED')?'PASS':'FAIL';
    await atomicJson(join(directory,'public/delivery-report.json'),report);
  }
  return report;
}
if(process.argv[1]&&import.meta.url===pathToFileURL(resolve(process.argv[1])).href){
  try{const input=options(process.argv.slice(2),['directory','deployment','jar'],['keep']);const result=await runDelivery(input);console.log('Delivery '+result.status);process.exitCode=result.status==='PASS'?0:1;}
  catch{console.error('DELIVERY_FAILED');process.exitCode=1;}
}
