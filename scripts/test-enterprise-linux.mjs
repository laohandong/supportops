import {mkdir,readFile,writeFile} from 'node:fs/promises';
import {resolve,join} from 'node:path';
import {randomUUID} from 'node:crypto';
import {parse,run,hash} from './enterprise/runtime.mjs';
const options=parse(process.argv.slice(2));
const directory=resolve(options.directory),bundle=resolve(options.bundle),root=resolve(new URL('..',import.meta.url).pathname.replace(/^\/([A-Za-z]):/,'$1:'));
await mkdir(directory,{recursive:false});
const id=randomUUID(),name=`so-enterprise-linux-${id.slice(0,8)}`,volume=`${name}-work`;
const report={kind:'ENTERPRISE_LINUX_SUITE_V1',suiteId:id,status:'RUNNING',resources:[],startedAt:new Date().toISOString()};
try{
 const manifest=JSON.parse(await readFile(join(bundle,'manifest.json'),'utf8'));
 if(manifest.kind!=='ENTERPRISE_BUNDLE_V1')throw new Error('BUNDLE_REQUIRED');
 report.jarSha256=manifest.jarSha256;report.bundleManifestSha256=await hash(join(bundle,'manifest.json'));
 report.validationSources={};for(const file of ['scripts/test-enterprise-linux.mjs','scripts/enterprise/linux-acceptance.mjs','scripts/enterprise/followup-acceptance.mjs','scripts/enterprise/FaultWindow.java','scripts/enterprise/runtime.mjs','src/test/e2e/enterprise-model-fixture.mjs','src/test/e2e/enterprise-source-fault-fixture.mjs'])report.validationSources[file]=await hash(join(root,file));
 await run(['volume','create','--label',`io.supportops.enterprise-tests=${id}`,volume]);
 const details=JSON.parse((await run(['volume','inspect',volume])).stdout)[0];
 if(details.Labels?.['io.supportops.enterprise-tests']!==id||!details.Mountpoint.endsWith(`/volumes/${volume}/_data`))throw new Error('TEST_VOLUME_IDENTITY_MISMATCH');
 report.resources.push({type:'volume',name:volume,mountpoint:details.Mountpoint,status:'RETAINED'});
 const image=JSON.parse((await run(['image','inspect','supportops-reliability-linux:20260929'])).stdout)[0];report.toolboxImageId=image.Id;
 const created=await run(['create','--name',name,'--label',`io.supportops.enterprise-tests=${id}`,'--network','host',
  '--mount',`type=volume,source=${volume},target=${details.Mountpoint}`,
  '--mount',`type=bind,source=${root},target=/source,readonly`,
  '--mount',`type=bind,source=${bundle},target=/bundle,readonly`,
  '--mount','type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock',
  '--entrypoint','node',image.Id,'/source/scripts/enterprise/linux-acceptance.mjs',`--directory=${details.Mountpoint}`,`--suite-id=${id}`]);
 const container=created.stdout.trim();report.resources.push({type:'container',id:container,name,status:'RETAINED'});
 await writeFile(join(directory,'resources.json'),JSON.stringify(report,null,2));
 let output;
 try{output=await run(['start','--attach',container],{timeout:1800000,maxBuffer:2*1024*1024});}
 catch(error){output={stdout:error.stdout||'',stderr:error.stderr||''};}
 await writeFile(join(directory,'console.log'),output.stdout+output.stderr);
 const actual=JSON.parse((await run(['inspect',container])).stdout)[0];report.exitCode=actual.State.ExitCode;report.running=actual.State.Running;
 if(actual.State.Running)throw new Error('SUITE_STILL_RUNNING');
 await mkdir(join(directory,'captured'));
 await run(['cp',`${container}:${details.Mountpoint}/public/.`,join(directory,'captured')],{timeout:120000});
 const result=JSON.parse(await readFile(join(directory,'captured/report.json'),'utf8'));report.result=result;
 report.status=actual.State.ExitCode===0&&result.status==='PASSED'?'PASSED':'FAILED_RETAINED';
}catch(error){report.status='FAILED_RETAINED';report.error=/^[A-Z_]+$/.test(error.message)?error.message:'ENTERPRISE_LINUX_FAILED';}
report.finishedAt=new Date().toISOString();await writeFile(join(directory,'report.json'),JSON.stringify(report,null,2));
console.log(JSON.stringify({status:report.status,report:join(directory,'report.json')}));if(report.status!=='PASSED')process.exitCode=1;
