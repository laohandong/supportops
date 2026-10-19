import assert from 'node:assert/strict';
import {readFile,mkdir} from 'node:fs/promises';
import {resolve,join} from 'node:path';
import {randomUUID} from 'node:crypto';
import {options,privateDirectory,atomicJson} from './reliability/common.mjs';
import {verifyDeployment,cleanDeployment,docker} from './reliability/deployment.mjs';

/** 真实 Docker 资源验证同项目未知对象阻止清理；不停止被测部署。 */
const input=options(process.argv.slice(2),['deployment','directory']);
if(!input.deployment)throw new Error('DEPLOYMENT_REQUIRED');
const deployment=JSON.parse(await readFile(resolve(input.deployment),'utf8'));
const directory=resolve(input.directory||join('.local/reliability-isolation',randomUUID()));
await privateDirectory(directory);await mkdir(join(directory,'public'));
await verifyDeployment(deployment);
const identity=async()=>Object.fromEntries(await Promise.all(Object.entries(deployment.resources).map(async([service,resource])=>{
  const current=JSON.parse((await docker(deployment,['inspect',resource.id])).stdout)[0];
  // Docker inspect 不保证挂载数组顺序；按唯一目标路径排序，仍比较全部挂载字段。
  const mounts=[...current.Mounts].sort((left,right)=>left.Destination.localeCompare(right.Destination));
  return [service,{id:current.Id,phase:current.State.Status,image:current.Image,mounts}];
})));
const before=await identity(),owner=randomUUID(),name='so-rd-guard-'+owner.slice(0,8),report={schemaVersion:1,platform:process.platform,cases:[],status:'RUNNING'};
let container,volume;
try{
  container=(await docker(deployment,['create','--name',name,'--label','com.docker.compose.project='+deployment.project,'--label','io.supportops.deployment='+owner,'--entrypoint','/bin/true',deployment.resources.minio.image])).stdout.trim();
  assert.match(container,/^[a-f0-9]{64}$/);
  await assert.rejects(cleanDeployment(deployment),/CLEANUP_UNREGISTERED_CONTAINERS/);
  assert.deepEqual(await identity(),before);
  const created=JSON.parse((await docker(deployment,['inspect',container])).stdout)[0];
  assert.equal(created.Config.Labels['io.supportops.deployment'],owner);assert.equal(created.Id,container);
  await docker(deployment,['rm','-v',container]);container=undefined;
  report.cases.push({id:'S01',status:'PASS',refusal:'CLEANUP_UNREGISTERED_CONTAINERS',originalResourcesUnchanged:true});
  volume=name+'-volume';await docker(deployment,['volume','create','--name',volume,'--label','com.docker.compose.project='+deployment.project,'--label','io.supportops.deployment='+owner]);
  await assert.rejects(cleanDeployment(deployment),/CLEANUP_UNREGISTERED_VOLUMES/);
  assert.deepEqual(await identity(),before);
  report.cases.push({id:'S02',status:'PASS',refusal:'CLEANUP_UNREGISTERED_VOLUMES',originalResourcesUnchanged:true});
  report.status='PASS';
}catch(error){report.status='FAIL';report.errorCode=/^[A-Z_]+$/.test(error.message)?error.message:'ISOLATION_ASSERTION_FAILED';await atomicJson(join(directory,'failure-private.json'),{message:error.message,stack:error.stack});process.exitCode=1;}
finally{
  if(container){const created=JSON.parse((await docker(deployment,['inspect',container])).stdout)[0];assert.equal(created.Id,container);assert.equal(created.Config.Labels['io.supportops.deployment'],owner);await docker(deployment,['rm','-v',container]);}
  if(volume){const created=JSON.parse((await docker(deployment,['volume','inspect',volume])).stdout)[0];assert.equal(created.Name,volume);assert.equal(created.Labels['io.supportops.deployment'],owner);await docker(deployment,['volume','rm',volume]);}
  await atomicJson(join(directory,'public/isolation-report.json'),report);
}
console.log('Isolation '+report.status);
