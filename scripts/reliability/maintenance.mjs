import {writeFile,readFile,unlink} from 'node:fs/promises';
import {join} from 'node:path';
import {randomUUID} from 'node:crypto';
import {javaProbe,javaExecutable} from './common.mjs';

/** 备份和写入者启动使用同一把独占锁；不通过年龄、重试或覆盖清除未知持有者。 */
export async function maintenanceLease(deployment, jar, kind, intentId, env = process.env) {
  const path=join(deployment.directory,'maintenance.lock'),token=randomUUID();
  const owner=await javaProbe(javaExecutable(env),jar,'io.supportops.ops.ProcessProbe',[String(process.pid)],{env,timeout:15000});
  if (owner.state!=='ALIVE'||!owner.processStartedAt) throw new Error('MAINTENANCE_OWNER_UNKNOWN');
  try { await writeFile(path,JSON.stringify({schemaVersion:1,deploymentId:deployment.deploymentId,kind,intentId,token,pid:process.pid,processStartedAt:owner.processStartedAt,state:'HELD'}),{flag:'wx',mode:0o600}); }
  catch(error) { if(error.code==='EEXIST')throw new Error('MAINTENANCE_LOCKED'); throw error; }
  return async()=>{ if(JSON.parse(await readFile(path,'utf8')).token!==token)throw new Error('MAINTENANCE_LOCK_CHANGED'); await unlink(path); };
}

/** 固定恢复安全参数由本地入口生成，不能从网页或备份正文扩展能力。 */
export function restoreArguments(directory) {
  return ['--supportops.feishu.enabled=false','--supportops.evaluation.enabled=false','--supportops.knowledge.jobs.enabled=false',
    '--supportops.model.provider=disabled','--supportops.embedding.provider=disabled','--supportops.rerank.enabled=false',`--supportops.evaluation.run-root=${join(directory,'evaluation-new')}`];
}
