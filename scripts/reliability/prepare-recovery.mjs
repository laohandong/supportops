import {readFile, writeFile} from 'node:fs/promises';
import {createWriteStream} from 'node:fs';
import {join, resolve} from 'node:path';
import {launch} from './runtime.mjs';
import {loadLocalConfig} from '../local-config.mjs';
import {systemEnvironment} from '../lib/runtime-safety.mjs';
import {registerRuntime} from './deployment.mjs';
import {connection, closeDatabase, query} from './storage.mjs';
import {atomicJson, until, digest} from './common.mjs';
import {startEvaluationProvider} from '../../src/test/js/fixtures/evaluation-provider.mjs';
import {authenticatedApi, seedRecovery} from './synthetic-data.mjs';
import {backupPlan} from './backup-v2.mjs';

/** 为停写演练准备真实业务数据，运行及夹具均在 finally 中按自身身份结束。 */
export async function prepareRecovery(source, directory, jar, root) {
  const data = JSON.parse(await readFile(join(source, 'suite-private.json'),'utf8'));
  const deployment = JSON.parse(await readFile(join(source, 'deployment/deployment.json'),'utf8'));
  const provider = await startEvaluationProvider();
  const env = await loadLocalConfig(deployment.config,systemEnvironment());
  env.SUPPORTOPS_MODEL_BASE_URL=`http://127.0.0.1:${provider.port}/v1`;
  const log=createWriteStream(join(directory,'seed-application.log'));
  let runtime, seeded;
  try {
    runtime=await launch({root,env,jar,runtimeRoot:deployment.runtimeRoot,cwd:deployment.directory,settings:deployment.settings,providers:'',port:0,
      deployment:join(deployment.directory,'deployment.json'),applicationArgs:['--spring.config.location=classpath:application.yml','--supportops.knowledge.jobs.scan-ms=500'],log});
    await registerRuntime(deployment,runtime);
    await writeFile(deployment.config,Object.entries(env).filter(([key])=>key.startsWith('SUPPORTOPS_')).map(([key,value])=>key+'='+value).join('\n')+'\n',{mode:0o600});
    let refused;
    try { await backupPlan(deployment,join(directory,'must-not-create-live-plan.json')); }
    catch(error) { refused=error.message; }
    if (refused!=='APPLICATION_NOT_CONFIRMED_STOPPED') throw new Error('LIVE_APPLICATION_BACKUP_NOT_REFUSED:'+refused);
    const api=await authenticatedApi(runtime.base,data.accounts.admin);
    seeded=await seedRecovery(api,`http://127.0.0.1:${provider.port}`);
    const database=await connection(deployment);
    try {
      await until(async()=>Number((await query(database,"SELECT COUNT(*) AS total FROM supportops.knowledge_tasks WHERE status IN ('PENDING','RUNNING','RETRY_WAIT')"))[0].total)===0,120000,'SEED_WRITERS_NOT_IDLE');
    } finally { await closeDatabase(database); }
    await atomicJson(join(directory,'seed-private.json'),seeded);
    await atomicJson(join(directory,'fixture-requests-private.json'),provider.requests);
    await runtime.stop();
    // 此单点故障数据只用于 UNKNOWN 恢复验证；真正三阶段子 JVM 崩溃另有 D01 证据。
    const database2=await connection(deployment);
    try {
      const original=await query(database2,'SELECT * FROM supportops.remediation_executions WHERE id=?',[seeded.unknownExecutionId]);
      await atomicJson(join(directory,'synthetic-state-ledger-private.json'),{kind:'SYNTHETIC_RESTORE_UNKNOWN',executionId:seeded.unknownExecutionId,original});
      await query(database2,"UPDATE supportops.remediation_executions SET status='UNKNOWN',application_status='UNKNOWN',verification_status='UNKNOWN',receipt_json=NULL,error_code='SYNTHETIC_RESTORE_UNKNOWN' WHERE id=? AND status='SUCCEEDED'",[seeded.unknownExecutionId]);
    } finally { await closeDatabase(database2); }
    if (await digest(deployment.jar)!==await digest(jar)) throw new Error('SEED_JAR_MISMATCH');
    return {deployment,seeded,data,liveRefusal:refused};
  } finally { if(runtime)await runtime.stop(); await provider.close(); }
}
