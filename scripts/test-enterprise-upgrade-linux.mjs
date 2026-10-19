import {mkdir,readFile,writeFile} from 'node:fs/promises';
import {resolve,join} from 'node:path';
import {randomUUID} from 'node:crypto';
import {parse,run,hash} from './enterprise/runtime.mjs';
const input=parse(process.argv.slice(2)),directory=resolve(input.directory),root=resolve('.');
await mkdir(directory);
const suite=randomUUID(),name=`so-enterprise-upgrade-${suite.slice(0,8)}`,volume=name+'-work';
const report={kind:'ENTERPRISE_UPGRADE_LINUX_V1',suiteId:suite,status:'RUNNING',resources:[],startedAt:new Date().toISOString()};
try{
 const jar=resolve(input.jar),legacy=resolve(input['legacy-jar']);report.jarSha256=await hash(jar);report.legacyJarSha256=await hash(legacy);
 const drive=/^([A-Za-z]):[\\/](.*)$/.exec(root);if(!drive)throw Error('WINDOWS_SOURCE_REQUIRED');
 const hostRoot=`/run/desktop/mnt/host/${drive[1].toLowerCase()}/${drive[2].replaceAll('\\','/')}`;
 await run(['volume','create','--label',`io.supportops.enterprise-tests=${suite}`,volume]);
 const details=JSON.parse((await run(['volume','inspect',volume])).stdout)[0];
 if(details.Labels?.['io.supportops.enterprise-tests']!==suite)throw Error('VOLUME_IDENTITY_MISMATCH');
 report.resources.push({type:'volume',name:volume,mountpoint:details.Mountpoint,state:'RETAINED'});
 const image=JSON.parse((await run(['image','inspect','supportops-reliability-linux:20260929'])).stdout)[0];report.toolboxImageId=image.Id;
 const created=await run(['create','--name',name,'--label',`io.supportops.enterprise-tests=${suite}`,'--network','host',
  '--mount',`type=volume,source=${volume},target=/work`,
  '--mount',`type=bind,source=${root},target=/source,readonly`,
  '--mount',`type=bind,source=${root},target=${hostRoot},readonly`,
  '--mount',`type=bind,source=${jar},target=/input/application.jar,readonly`,
  '--mount',`type=bind,source=${legacy},target=/input/legacy.jar,readonly`,
  '--mount','type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock',
  '--env',`SUPPORTOPS_RD_COMPOSE_ROOT=${hostRoot}`,'--entrypoint','sh',image.Id,'-c',
  'mkdir /work/repo && cp -a /source/scripts /source/src /source/infrastructure /source/examples /source/evaluation /source/node_modules /source/package.json /source/pom.xml /work/repo/ && cd /work/repo && node scripts/test-enterprise-upgrade.mjs --directory=/work/upgrade --jar=/input/application.jar --legacy-jar=/input/legacy.jar --keep']);
 const container=created.stdout.trim();report.resources.push({type:'container',id:container,name,state:'RETAINED'});
 await writeFile(join(directory,'resources.json'),JSON.stringify(report,null,2));
 let result;try{result=await run(['start','--attach',container],{timeout:1800000});}catch(error){result={stdout:error.stdout||'',stderr:error.stderr||''};}
 await writeFile(join(directory,'console.log'),result.stdout+result.stderr);
 const state=JSON.parse((await run(['inspect',container])).stdout)[0];report.exitCode=state.State.ExitCode;
 if(state.State.Running)throw Error('SUITE_STILL_RUNNING');
 await run(['cp',`${container}:/work/upgrade/public`,join(directory,'captured')]);
 report.result=JSON.parse(await readFile(join(directory,'captured/report.json'),'utf8'));
 report.status=state.State.ExitCode===0&&report.result.status==='PASS'?'PASSED':'FAILED_RETAINED';
}catch(error){report.status='FAILED_RETAINED';report.error=/^[A-Z_]+$/.test(error.message)?error.message:'UPGRADE_LINUX_FAILED';}
report.finishedAt=new Date().toISOString();await writeFile(join(directory,'report.json'),JSON.stringify(report,null,2));
console.log(JSON.stringify({status:report.status,report:join(directory,'report.json')}));if(report.status!=='PASSED')process.exitCode=1;
