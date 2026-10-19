import assert from 'node:assert/strict';
import {join} from 'node:path';
import {readFile} from 'node:fs/promises';
import {createWriteStream} from 'node:fs';
import {randomUUID} from 'node:crypto';
import {launch} from './runtime.mjs';
import {restoreArguments} from './maintenance.mjs';
import {loadLocalConfig} from '../local-config.mjs';
import {systemEnvironment} from '../lib/runtime-safety.mjs';
import {hash,atomicJson,digest,until,files} from './common.mjs';
import {databaseSnapshot} from './storage.mjs';
import {rows} from './recovery-checks.mjs';
import {authenticatedApi} from './synthetic-data.mjs';
import {startEvaluationProvider} from '../../src/test/js/fixtures/evaluation-provider.mjs';

/** 首次恢复启动的合法变更逐行核对；其他业务表保持摘要不变。 */
async function startupDifference(deployment,before,plansBefore,eventsBefore,directory){
  const after=await databaseSnapshot(deployment),plansAfter=await rows(deployment,'SELECT * FROM supportops.remediation_plans');
  const old=new Map(plansBefore.map(row=>[row.id,row])),changed=[];
  assert.equal(plansAfter.length,plansBefore.length);
  for(const current of plansAfter){
    const original=old.get(current.id);assert.ok(original);
    if(['PENDING_APPROVAL','APPROVED'].includes(original.status)){
      assert.deepEqual(current,{...original,status:'STALE',version:original.version+1});changed.push(current.id);
    }else assert.deepEqual(current,original);
  }
  const eventsAfter=await rows(deployment,'SELECT * FROM supportops.remediation_events'),oldEvents=new Map(eventsBefore.map(row=>[String(row.id),row]));
  const newEvents=eventsAfter.filter(row=>!oldEvents.has(String(row.id)));
  for(const original of eventsBefore)assert.deepEqual(eventsAfter.find(row=>String(row.id)===String(original.id)),original);
  assert.equal(newEvents.length,changed.length);assert.ok(newEvents.every(row=>row.kind==='INSTANCE_CHANGED'));
  assert.deepEqual(newEvents.map(row=>row.plan_id).sort(),changed.sort());
  for(const[schema,tables]of Object.entries(before.schemas))for(const[table,value]of Object.entries(tables)){
    if(schema===deployment.database&&['remediation_plans','remediation_events'].includes(table))continue;
    assert.deepEqual(after.schemas[schema][table],value,schema+'.'+table);
  }
  await atomicJson(join(directory,'startup-difference-private.json'),{before,after,plansBefore,plansAfter,eventsBefore,eventsAfter,allowedFields:['remediation_plans.status','remediation_plans.version','remediation_events INSTANCE_CHANGED insert']});
  return {changedPlans:changed.length,addedInstanceChangedEvents:newEvents.length,allOtherTablesUnchanged:true};
}

/** 恢复的页面、业务 API、原件、Excel 和真实索引都使用原账号进行验收。 */
export async function acceptRestore(root,restored,prepared,directory){
  const deployment=restored.deployment,seed=prepared.seeded,data=prepared.data;
  const before=await databaseSnapshot(deployment),plans=await rows(deployment,'SELECT * FROM supportops.remediation_plans'),events=await rows(deployment,'SELECT * FROM supportops.remediation_events');
  const unknownBefore=await rows(deployment,'SELECT * FROM supportops.remediation_executions WHERE id=?',[seed.unknownExecutionId]);
  assert.equal(unknownBefore[0].status,'UNKNOWN');
  const sourceMaterials=Object.fromEntries(await Promise.all(restored.manifest.materials.map(async material=>[material.attemptId,await rows(deployment,'SELECT workspace_path,process_json,resources_json FROM supportops.evaluation_attempts WHERE id=?',[material.attemptId])])));
  const env=await loadLocalConfig(deployment.config,systemEnvironment());
  const archivedSettings=JSON.parse(await readFile(join(deployment.directory,'archived-model-settings.json'),'utf8'));
  const archivedEmbedding=archivedSettings.roles?.embedding,endpoint=new URL(archivedEmbedding?.['base-url']);
  assert.equal(endpoint.protocol,'http:');assert.equal(endpoint.hostname,'127.0.0.1');assert.equal(endpoint.pathname,'/v1');
  assert.equal(archivedEmbedding.name,'rd-recovery-fixture');assert.equal(archivedEmbedding['api-key'],'synthetic-rd-recovery-fixture');
  // 兼容性摘要包含端点；重启本次已关闭的同一回环夹具，不能把旧向量视为新端点的结果。
  const provider=await startEvaluationProvider(Number(endpoint.port));
  let runtime,browser;
  try{
    runtime=await launch({root,env,jar:deployment.jar,runtimeRoot:deployment.runtimeRoot,cwd:deployment.directory,settings:deployment.settings,providers:'',port:0,
      deployment:join(deployment.directory,'deployment.json'),applicationArgs:['--spring.config.location=classpath:application.yml',...restoreArguments(deployment.directory)],log:createWriteStream(join(directory,'restore-application.log'))});
    const differences=await startupDifference(deployment,before,plans,events,directory);
    const api=await authenticatedApi(runtime.base,data.accounts.admin),ordinary=await authenticatedApi(runtime.base,data.accounts.user);
    assert.equal((await api('GET','/ops/runtime')).jarSha256,restored.manifest.jarSha256);
    await ordinary('GET','/ops/runtime',undefined,403);await ordinary('GET','/runs/'+seed.archivedRunId,undefined,404);
    assert.ok(!(await api('GET','/runs')).some(run=>run.id===seed.archivedRunId));
    assert.equal((await api('GET','/runs/'+seed.archivedRunId)).id,seed.archivedRunId);
    const role=await api('GET','/auth/me');assert.equal(role.role,'ADMIN');
    const originals=[];
    for(const version of [seed.old,seed.current,seed.excel]){
      const bytes=await api('GET','/documents/versions/'+version.versionId+'/original');assert.equal(hash(bytes),version.originalSha256);originals.push({versionId:version.versionId,size:bytes.length,sha256:hash(bytes)});
    }
    const excel=await api('POST','/documents/datasets/'+seed.dataset.id+'/query',{version:'2.0',sql:'SELECT source_row,c_0,c_1 FROM data ORDER BY source_row'});
    assert.deepEqual(excel,seed.excelQuery);
    const lexical=await api('GET','/documents/search?query=sync.targetPath&version=2.0&lexicalOnly=true');assert.equal(lexical.mode,'LEXICAL');
    assert.ok(JSON.stringify(lexical).includes('CURRENT_VERSION_SENTINEL'));assert.ok(!JSON.stringify(lexical).includes('OLD_VERSION_SENTINEL'));
    const sourcePackage=await api('GET','/evaluation-exports/'+seed.exportId);assert.deepEqual(sourcePackage,seed.sourcePackage);
    for(const[attemptId,original]of Object.entries(sourceMaterials))assert.deepEqual(await rows(deployment,'SELECT workspace_path,process_json,resources_json FROM supportops.evaluation_attempts WHERE id=?',[attemptId]),original);
    assert.deepEqual(await rows(deployment,'SELECT * FROM supportops.remediation_executions WHERE id=?',[seed.unknownExecutionId]),unknownBefore);
    const oldApproval=await api('GET','/remediations/'+seed.oldApproval.id);assert.equal(oldApproval.status,'STALE');assert.equal(oldApproval.canExecute,false);
    const rejected=await api('POST','/remediations/'+seed.oldApproval.id+'/execute',{requestId:randomUUID(),expectedVersion:seed.oldApproval.version,planHash:seed.oldApproval.planHash},409);
    assert.equal(Number((await rows(deployment,'SELECT COUNT(*) AS total FROM supportops.remediation_executions WHERE plan_id=?',[seed.oldApproval.id]))[0].total),0);
    assert.equal(provider.requests.length,0);
    // 只有显式的回环嵌入夹具用于核对已恢复向量；对话及外发始终关闭。
    const settings=await api('GET','/model-settings');
    await api('POST','/model-settings/embedding',{provider:'custom',model:'rd-recovery-fixture',baseUrl:`http://127.0.0.1:${provider.port}/v1`,apiKey:'synthetic-rd-recovery-fixture',keyAction:'replace',revision:settings.revision});
    try{
      const vector=await api('GET','/documents/search?query=sync.targetPath&version=2.0&lexicalOnly=false');assert.equal(vector.mode,'HYBRID');
      assert.ok(JSON.stringify(vector).includes('CURRENT_VERSION_SENTINEL'));assert.ok(!JSON.stringify(vector).includes('OLD_VERSION_SENTINEL'));
      assert.ok(provider.requests.length>0);assert.ok(provider.requests.every(request=>request.path==='/v1/embeddings'));
      await atomicJson(join(directory,'vector-private.json'),{vector,requests:provider.requests});
    }finally{
      const latest=await api('GET','/model-settings');await api('POST','/model-settings/embedding',{provider:'disabled',model:'',baseUrl:'',keyAction:'remove',revision:latest.revision});
    }
    const {chromium,expect}=await import('@playwright/test');browser=await chromium.launch({headless:true});
    const context=await browser.newContext({baseURL:runtime.base,viewport:{width:1440,height:960}}),page=await context.newPage();
    await page.goto('/login.html');await page.locator('#username').fill(data.accounts.admin.username);await page.locator('#password').fill(data.accounts.admin.password);await page.locator('#login-submit').click();
    await page.locator('#current-user').filter({hasText:data.accounts.admin.username}).waitFor();
    await page.locator('.nav-item[data-page="knowledge"]').click();await page.locator('#page-knowledge').waitFor();
    await until(async()=>((await page.locator('#page-knowledge').innerText()).includes('RD 恢复合成资料')),15000,'RESTORED_DOCUMENT_UI_MISSING');
    await page.screenshot({path:join(directory,'public/B02-restored-documents.png'),fullPage:true,mask:[page.locator('input[type=password]')],animations:'disabled'});
    await page.locator('.nav-item[data-page="remediation"]').click();await page.getByRole('button',{name:'RD 恢复前旧审批',exact:true}).click();
    await expect(page.locator('#remediation-content')).toContainText('环境已变化');await expect(page.getByRole('button',{name:'执行已批准修复',exact:true})).toBeDisabled();
    await page.screenshot({path:join(directory,'public/B06-old-approval.png'),fullPage:true,mask:[page.locator('input[type=password]')],animations:'disabled'});
    await page.getByRole('button',{name:'返回列表',exact:true}).click();
    await page.getByRole('button',{name:'RD 历史执行合成故障状态',exact:true}).click();await expect(page.locator('#remediation-content')).toContainText('结果待核查');
    await page.screenshot({path:join(directory,'public/B06-unknown.png'),fullPage:true,mask:[page.locator('input[type=password]')],animations:'disabled'});
    await atomicJson(join(directory,'restored-business-private.json'),{differences,originals,excel,lexical,sourcePackage,sourceMaterials,unknownBefore,oldApproval,rejected,jarSha256:await digest(deployment.jar)});
    return {differences,loginWithRestoredAccounts:true,permissions:{ordinaryRuntime:403,foreignRun:404},originals:originals.length,excelRows:excel.rows.length,
      lexicalVersionFiltered:true,hybridVersionFiltered:true,archivedHistoryReadable:true,reviewsReadable:true,unknownUnchanged:true,oldApprovalExecution:409,
      historicalPathsAndProcessesUnchanged:true,sourceResourcesTouched:false,bootModelRequests:0,realModelRequests:0,vector:'LOOPBACK_PROTOCOL_FIXTURE_ONLY',browser:'CHROMIUM_ACTUAL_BACKEND'};
  }finally{if(browser)await browser.close();if(runtime)await runtime.stop();await provider.close();}
}
