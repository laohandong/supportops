import assert from 'node:assert/strict';
import {docker,verifyDeployment} from './deployment.mjs';
import {until} from './common.mjs';
import {setTimeout as pause} from 'node:timers/promises';
import {stoppedWriters} from './backup-v2.mjs';

/** 退出连接可能仍短暂出现在MySQL中；只读重查停写证明，始终拒绝仍存在的未知写入者。 */
export async function confirmedStoppedWriters(deployment,{timeoutMs=15000,intervalMs=1000,check=stoppedWriters,now=Date.now,wait=pause}={}) {
  const deadline=now()+timeoutMs,rechecks=[];
  while(true) {
    try {return {proof:await check(deployment),rechecks};}
    catch(error) {
      if(error.message!=='UNKNOWN_DATABASE_WRITER'||now()>=deadline)throw error;
      rechecks.push({observedAt:new Date(now()).toISOString(),errorCode:error.message});
      await wait(intervalMs);
    }
  }
}

/** 完整备份和真实停写证明后暂停本套源存储；目标验证结束再恢复原身份，不放宽容量或停写门槛。 */
export async function suspendAfterBackup(deployment,backup) {
  assert.equal(backup.status,'COMPLETE');assert.equal(backup.deploymentId,deployment.deploymentId);
  await verifyDeployment(deployment);const stoppedWriterProof=await confirmedStoppedWriters(deployment);
  const expected=Object.values(deployment.resources).map(value=>value.id).sort();
  const observed=(await docker(deployment,['ps','--all','--quiet','--no-trunc','--filter','label=com.docker.compose.project='+deployment.project])).stdout.trim().split(/\r?\n/).filter(Boolean).sort();
  assert.deepEqual(observed,expected);
  await docker(deployment,['stop','--time','30',...expected],{timeout:60000});
  const stopped=JSON.parse((await docker(deployment,['inspect',...expected])).stdout);assert.ok(stopped.every(value=>!value.State.Running));
  const evidence={deploymentId:deployment.deploymentId,backupId:backup.backupId,containerIds:expected,stoppedWriterProof,stoppedAt:new Date().toISOString()};
  return {evidence,resume:async()=>{
    await verifyDeployment(deployment);await docker(deployment,['start',...expected],{timeout:60000});
    await until(async()=>{const states=JSON.parse((await docker(deployment,['inspect',...expected])).stdout);return states.every(value=>value.State.Running&&(!value.State.Health||value.State.Health.Status==='healthy'));},180000,'SOURCE_STORAGE_RESTART_TIMEOUT');
    evidence.resumedAt=new Date().toISOString();
  }};
}
