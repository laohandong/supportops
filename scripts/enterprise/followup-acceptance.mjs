import assert from 'node:assert/strict';
import {readFile,writeFile,copyFile,unlink,stat} from 'node:fs/promises';
import {join} from 'node:path';
import {randomUUID} from 'node:crypto';
import {createRequire} from 'node:module';
import {spawn} from 'node:child_process';
import {run} from './runtime.mjs';
const pause=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const exists=async path=>Boolean(await stat(path).catch(error=>{if(error.code==='ENOENT')return null;throw error;}));
/** 独立SQL只接受本套件固定语句与已校验UUID；密码始终从容器私有挂载读取。 */
export function suiteSql(state){
 const mysql=state.containers.find(value=>value.Config.Labels['com.docker.compose.service']==='mysql');
 const shell='MYSQL_PWD="$(cat /run/secrets/mysql-root)" exec mysql --batch --raw --skip-column-names --unbuffered -uroot supportops -e "$1"';
 return {mysql,shell,query:async sql=>(await run(['exec',mysql.Id,'sh','-c',shell,'--',sql])).stdout.trim()};
}
/** 在真实HTTPS、数据库、最终JAR和Chromium上补充可复现的企业边界。 */
export async function runFollowupChecks(ctx){
 const {check,call,request,origin,privateDir,publicDir,sourceState,environments,token,newApplication,newKey,modelCalls,waitCalls,waitRun,appContainer}=ctx;
 const sql=suiteSql(sourceState),environment=environments[0];
 const waitManagedRun=async id=>{for(let attempt=0;attempt<240;attempt++){const value=(await call('/api/runs/'+id)).body;if(!['QUEUED','RUNNING'].includes(value.status))return value;await pause(100);}throw new Error('MANAGED_RUN_TERMINAL_TIMEOUT');};
 const resourceState=async()=> (await call('/api/enterprise/resources')).body.find(value=>value.id===environment.environment.id);
 const changeTarget=async status=>{const value=await resourceState();return call('/api/enterprise/environments',{requestId:randomUUID(),id:value.id,parentId:value.parentId,code:value.code,name:value.name,productVersion:value.productVersion,status,expectedVersion:value.version});};
 const connector=async(config,credentialSourceRevisionId=environment.connector.id)=> (await call('/api/enterprise/connectors',{requestId:randomUUID(),environmentId:environment.environment.id,type:'STANDARD_REST',config,secretAction:'KEEP',credentialSourceRevisionId})).body;
 const activate=async revision=>{const value=await resourceState();return call('/api/enterprise/connectors/enable',{requestId:randomUUID(),revisionId:revision.id,expectedVersion:value.version});};
 await check('EIT-10-OPTIONAL','缺可选能力、启用和源认证/授权/不可用故障',async()=>{
  await copyFile('/source/src/test/e2e/enterprise-source-fault-fixture.mjs',join(privateDir,'source-fault-fixture.mjs'));
  await writeFile(join(privateDir,'source-mode'),'minimal',{mode:0o600});
  const network=`supportops-${sourceState.metadata.deploymentId.slice(0,8)}_egress`,name=`so-followup-${sourceState.metadata.deploymentId.slice(0,8)}-optional`;
  const id=(await run(['run','--detach','--name',name,'--label',`io.supportops.enterprise-tests=${ctx.suite}`,'--network',network,'--network-alias','source-minimal','--mount',`type=bind,source=${privateDir},target=/private`,'--user','0:0','--entrypoint','node',sourceState.metadata.images.app.name,'/private/source-fault-fixture.mjs'])).stdout.trim();
  ctx.resources.push({type:'source',id,name,kind:'SYNTHETIC_OPTIONAL_SOURCE'});
  const details=JSON.parse((await run(['inspect',id])).stdout)[0],address=details.NetworkSettings.Networks[network].IPAddress;
  await pause(500);
  const config={baseUrl:'https://source-minimal:9443',allowedAddresses:[address],capabilities:['APP_INFO'],modelData:['APP_INFO'],allowLocalHttp:false};
  const minimal=await connector(config),tested=(await call('/api/enterprise/connectors/test',{requestId:randomUUID(),revisionId:minimal.id})).body;
  assert.equal(tested.testStatus,'PASSED');await activate(minimal);
  const accepted=(await call('/open-api/v1/diagnoses',{requestId:randomUUID(),environmentId:environment.environment.id,question:'仅必要能力的合成诊断',lexicalOnly:true},202,{token})).body;
  assert.equal((await waitRun(accepted.runId)).status,'COMPLETED');
  const all=await connector({...config,capabilities:['APP_INFO','EFFECTIVE_CONFIG','RECENT_LOGS','DOWNSTREAM_HEALTH']});
  assert.equal((await call('/api/enterprise/connectors/test',{requestId:randomUUID(),revisionId:all.id})).body.errorCode,'TARGET_CAPABILITY_MISSING');
  await call('/api/enterprise/connectors/enable',{requestId:randomUUID(),revisionId:all.id,expectedVersion:(await resourceState()).version},409);
  const failures=[];
  for(const [status,code]of [['401','TARGET_AUTHENTICATION_FAILED'],['403','TARGET_PERMISSION_DENIED'],['503','TARGET_HTTP_FAILED']]){
   await writeFile(join(privateDir,'source-mode'),status);
   const value=(await call('/api/enterprise/connectors/test',{requestId:randomUUID(),revisionId:minimal.id})).body;
   assert.equal(value.testStatus,'FAILED');assert.equal(value.errorCode,code);assert.ok(!JSON.stringify(value).includes('synthetic-private'));
   failures.push({status:Number(status),code});
  }
  // 后续验收恢复真实Java源并批准四种只读载荷，避免用最小能力配置掩盖撤销测试。
  const full=await connector({...environment.connector.config,modelData:['APP_INFO','EFFECTIVE_CONFIG','RECENT_LOGS','DOWNSTREAM_HEALTH']});
  assert.equal((await call('/api/enterprise/connectors/test',{requestId:randomUUID(),revisionId:full.id})).body.testStatus,'PASSED');await activate(full);environment.connector=full;
  return {minimalCapabilityEnabled:true,optionalMissingActivationRejected:true,failures,protocolFixture:true};
 });
 await check('EIT-23-ACTIVE','应用活动额度跨环境与取消后的名额归还',async()=>{
  const application=await newApplication('active-quota-followup',environments.map(value=>value.environment.id),{maxActive:1}),key=(await newKey(application)).secret;
  await writeFile(join(privateDir,'model.pause'),'pause',{mode:0o600});
  try{
   const before=await modelCalls(),first=(await call('/open-api/v1/diagnoses',{requestId:randomUUID(),environmentId:environments[0].environment.id,question:'应用额度首任务',lexicalOnly:true},202,{token:key})).body;await waitCalls(before+1);
   const deniedId=randomUUID(),denied=await call('/open-api/v1/diagnoses',{requestId:deniedId,environmentId:environments[1].environment.id,question:'应用额度跨环境',lexicalOnly:true},429,{token:key});
   assert.equal(denied.headers['retry-after'],'60');await call(`/open-api/v1/requests/${deniedId}`,undefined,404,{token:key});
   assert.equal(Number(await sql.query(`SELECT COUNT(*) FROM integration_requests WHERE request_id='${deniedId}'`)),0);
   await call(`/open-api/v1/diagnoses/${first.runId}/cancel`,{},200,{token:key});assert.equal((await call(`/open-api/v1/diagnoses/${first.runId}`,undefined,200,{token:key})).body.status,'CANCELLED');
   await unlink(join(privateDir,'model.pause'));await pause(300);
   const second=(await call('/open-api/v1/diagnoses',{requestId:randomUUID(),environmentId:environments[1].environment.id,question:'取消后跨环境成功',lexicalOnly:true},202,{token:key})).body;
   let terminal;for(let attempt=0;attempt<200;attempt++){terminal=(await call(`/open-api/v1/diagnoses/${second.runId}`,undefined,200,{token:key})).body;if(!['QUEUED','RUNNING'].includes(terminal.status))break;await pause(100);}
   assert.equal(terminal.status,'COMPLETED');return {maxActive:1,otherEnvironmentRejected:true,rejectedReceiptRows:0,afterCancellation:'COMPLETED'};
  }finally{await unlink(join(privateDir,'model.pause')).catch(()=>{});}
 });
 await check('EIT-24-MATRIX','人员/应用/目标撤销与四类只读工具的后续外发',async()=>{
  const user=(await call('/api/users',{username:'revocation_followup',password:'Synthetic!Revocation9',role:'USER'})).body;
  await call('/api/enterprise/project-members',{requestId:randomUUID(),resourceId:environment.system.parentId,userId:user.id,granted:true});
  const grant=async granted=>call('/api/enterprise/environment-grants',{requestId:randomUUID(),resourceId:environment.environment.id,userId:user.id,granted});await grant(true);
  const login=await request(origin,'/api/auth/login',{body:{username:'revocation_followup',password:'Synthetic!Revocation9'}});assert.equal(login.status,200);const userCookie=login.headers['set-cookie'][0].split(';')[0];
  const outcomes=[];
  for(const kind of ['PERSON','APPLICATION','TARGET'])for(const tool of ['get_app_info','get_effective_config','get_recent_logs','get_downstream_health']){
   const application=await newApplication(`revoke-${kind.toLowerCase()}-${outcomes.length}`, [environment.environment.id]),key=(await newKey(application)).secret;
   await writeFile(join(privateDir,'tool-choice'),tool);await writeFile(join(privateDir,'model.pause'),'pause',{mode:0o600});
   try{
    const before=await modelCalls();let accepted;
    if(kind==='PERSON'){const response=await request(origin,'/api/runs',{cookie:userCookie,body:{question:'人员撤销合成检查',sessionId:randomUUID(),environmentId:environment.environment.id,lexicalOnly:true}});assert.equal(response.status,200);accepted={runId:response.body.id};}
    else accepted=(await call('/open-api/v1/diagnoses',{requestId:randomUUID(),environmentId:environment.environment.id,question:'撤销后不能调用'+tool,lexicalOnly:true},202,{token:key})).body;
    await waitCalls(before+1);
    if(kind==='PERSON')await grant(false);
    else if(kind==='TARGET')await changeTarget('DISABLED');
    else await call('/api/enterprise/applications',{requestId:randomUUID(),id:application.id,projectId:application.projectId,code:application.code,name:application.name,permissions:application.permissions,environmentIds:application.environmentIds,status:'DISABLED',expectedVersion:application.version});
    await unlink(join(privateDir,'model.pause'));
    const terminal=await waitManagedRun(accepted.runId);assert.equal(terminal.status,'FAILED');assert.equal(terminal.errorCode,'AUTHORIZATION_REVOKED');assert.equal(await modelCalls(),before+1);
    const row=(await sql.query(`SELECT input_tokens,output_tokens FROM runs WHERE id='${accepted.runId}'`)).split('\t').map(Number);assert.ok(row[0]>0);assert.ok(row[1]>0);
    outcomes.push({kind,tool,errorCode:terminal.errorCode,subsequentModelCalls:0,usageRetained:true});
   }finally{await unlink(join(privateDir,'model.pause')).catch(()=>{});if(kind==='PERSON')await grant(true);if(kind==='TARGET')await changeTarget('ACTIVE');}
  }
  await unlink(join(privateDir,'tool-choice'));return {combinations:outcomes,embeddingInflight:'COVERED_BY_EIT18_VECTOR'};
 });
 await check('EIT-28-BROWSER','真实浏览器未知管理请求、刷新、SSE断线和权限变化',async()=>{
  const {chromium}=createRequire('/source/package.json')('@playwright/test'),browser=await chromium.launch({headless:true,args:['--no-sandbox']}),context=await browser.newContext({ignoreHTTPSErrors:true});
  await context.addCookies([{name:ctx.getCookie().split('=')[0],value:ctx.getCookie().split('=')[1],url:origin,secure:true}]);const page=await context.newPage();
  try{
   await page.goto(origin);await page.locator('[data-page=enterprise]').click();await page.locator('#enterprise-tree li').first().waitFor();
   let submitted=0,originalId,committed;
   await page.route('**/api/enterprise/projects',async route=>{if(route.request().method()!=='POST')return route.continue();submitted++;originalId=route.request().postDataJSON().requestId;committed=await route.fetch();assert.equal(committed.status(),200);await route.abort('failed');});
   await page.locator('[data-enterprise-create=PROJECT]').click();await page.locator('#enterprise-code').fill('unknown-followup');await page.locator('#enterprise-name').fill('未知请求复验项目');await page.locator('#enterprise-resource-submit').click();
   await page.locator('#enterprise-recovery').waitFor();assert.equal(await page.locator('#enterprise-original-request').textContent(),originalId);
   await page.reload();await page.locator('[data-page=enterprise]').click();await page.locator('#enterprise-recovery').waitFor();assert.equal(await page.locator('#enterprise-original-request').textContent(),originalId);
   await page.route('**/api/enterprise/requests/*',route=>route.fulfill({status:503,contentType:'application/json',body:'{"error":"SYNTHETIC_READ_FAILURE"}'}));
   await page.locator('#enterprise-recover').click();await page.waitForTimeout(300);assert.equal(submitted,1);assert.equal(await page.locator('#enterprise-recovery').isVisible(),true);
   await page.unroute('**/api/enterprise/requests/*');await page.locator('#enterprise-recover').click();await page.locator('#enterprise-recovery').waitFor({state:'hidden'});assert.equal(submitted,1);
   assert.equal(Number(await sql.query(`SELECT COUNT(*) FROM projects WHERE code='unknown-followup'`)),1);
   await page.locator('[data-page=diagnosis]').click();await page.locator('#diagnosis-environment').selectOption(environment.environment.id);
   const streams=[];await page.route('**/api/runs/*/stream?*',async route=>{streams.push(new URL(route.request().url()).searchParams.get('after'));if(streams.length===2)await route.abort('failed');else await route.continue();});
   await writeFile(join(privateDir,'model.pause'),'pause',{mode:0o600});const before=await modelCalls();
   const accepted=page.waitForResponse(value=>value.url().endsWith('/api/runs')&&value.request().method()==='POST');await page.locator('#question').fill('SSE恢复合成诊断');await page.locator('#start-run').click();const run=(await (await accepted).json()).id;await waitCalls(before+1);
   for(let attempt=0;attempt<100&&streams.length===0;attempt++)await pause(50);assert.ok(streams.length>0);
   await page.evaluate(()=>window.dispatchEvent(new Event('offline')));await page.reload();
   await page.locator('#history .history-item').filter({hasText:'SSE恢复合成诊断'}).click();
   for(let attempt=0;attempt<200&&streams.length<3;attempt++)await pause(50);assert.ok(streams.length>=3);assert.ok(streams.slice(1).some(value=>Number(value)>0));
   await unlink(join(privateDir,'model.pause'));await page.locator('#run-state').filter({hasText:'已完成'}).waitFor({timeout:120000});assert.equal(await modelCalls(),before+2);
   const events=(await call(`/api/runs/${run}/events`)).body;assert.equal(new Set(events.map(value=>value.id)).size,events.length);
   // 普通用户真实浏览器运行中撤销，刷新后目标与历史均按服务器权限收敛；恢复原授权只读旧任务。
   const user=(await call('/api/users')).body.find(value=>value.username==='revocation_followup');assert.ok(user);
   const grant=granted=>call('/api/enterprise/environment-grants',{requestId:randomUUID(),resourceId:environment.environment.id,userId:user.id,granted});
   const userContext=await browser.newContext({ignoreHTTPSErrors:true}),userPage=await userContext.newPage();
   await userPage.goto(origin+'/login.html');await userPage.locator('#username').fill('revocation_followup');await userPage.locator('#password').fill('Synthetic!Revocation9');await userPage.locator('#login-submit').click();await userPage.waitForURL(origin+'/');
   await userPage.locator('#diagnosis-environment').selectOption(environment.environment.id);let userPosts=0;
   await userPage.route('**/api/runs',route=>{if(route.request().method()==='POST')userPosts++;return route.continue();});
   await writeFile(join(privateDir,'model.pause'),'pause',{mode:0o600});const userBefore=await modelCalls();
   const userResponse=userPage.waitForResponse(value=>value.url().endsWith('/api/runs')&&value.request().method()==='POST');await userPage.locator('#question').fill('浏览器授权撤销验收');await userPage.locator('#start-run').click();const userRun=(await (await userResponse).json()).id;await waitCalls(userBefore+1);
   try{
    await grant(false);await unlink(join(privateDir,'model.pause'));const terminal=await waitManagedRun(userRun);assert.equal(terminal.status,'FAILED');assert.equal(terminal.errorCode,'AUTHORIZATION_REVOKED');
    await userPage.reload();await userPage.locator('#diagnosis-environment').waitFor();
    for(let attempt=0;attempt<100&&await userPage.locator('#diagnosis-environment option').count()===1;attempt++)await pause(50);
    assert.equal(await userPage.locator('#diagnosis-environment').evaluate((select,id)=>Array.from(select.options).some(option=>option.value===id),environment.environment.id),false);
    assert.equal((await userContext.request.get(origin+'/api/runs/'+userRun)).status(),404);
    assert.equal(await userPage.locator('#history .history-item').filter({hasText:'浏览器授权撤销验收'}).count(),0);assert.equal(userPosts,1);assert.equal(await modelCalls(),userBefore+1);
   }finally{await grant(true);await unlink(join(privateDir,'model.pause')).catch(()=>{});}
   await userPage.reload();await userPage.locator('#history .history-item').filter({hasText:'浏览器授权撤销验收'}).click();assert.equal(userPosts,1);assert.equal(await modelCalls(),userBefore+1);
   await userPage.screenshot({path:join(publicDir,'enterprise-permission-recovery.png'),fullPage:true});
   await page.screenshot({path:join(publicDir,'enterprise-followup-recovery.png'),fullPage:true});
   return {managementCommittedBeforeDisconnect:true,managementPosts:submitted,read503DidNotResubmit:true,refreshKeptRequestId:true,sseCursors:streams,eventIdsUnique:true,modelExecutions:1,browserPermissionRevocation:{runId:userRun,unauthorizedRead:404,hiddenTarget:true,hiddenHistory:true,posts:userPosts,restoredOriginalRun:true}};
  }finally{await unlink(join(privateDir,'model.pause')).catch(()=>{});await browser.close();}
 });
}
/** 原事务提交后在调度入口暂停真实最终JAR，独立SQL核对后终止JVM并检查重启不重放。 */
export async function committedBeforeScheduling(ctx){
 const {privateDir,sourceState,appContainer,check,call,token,modelCalls,waitRun,bundleDir,command,origin,request}=ctx;
 await check('EIT-17-COMMITTED','独立JVM提交后调度前退出与固定回执恢复',async()=>{
  const sql=suiteSql(sourceState),address=Object.values(appContainer.NetworkSettings.Networks).find(value=>value.IPAddress).IPAddress;
  const ready=join(privateDir,'debug-attached'),held=join(privateDir,'debug-held');
  await command('javac',['--release','21','-d',privateDir,'/source/scripts/enterprise/FaultWindow.java']);
  const debug=command('java',['-cp',privateDir,'FaultWindow',address,'18501',ready,held]).then(value=>({ok:true,...value}),error=>({ok:false,stdout:error.stdout||'',stderr:error.stderr||''}));
  for(let attempt=0;attempt<200&&!await exists(ready);attempt++)await pause(50);assert.equal(await exists(ready),true);
  const runtime=(await call('/api/ops/runtime')).body,before=await modelCalls(),requestId=randomUUID();
  const submission=call('/open-api/v1/diagnoses',{requestId,environmentId:ctx.environments[1].environment.id,question:'精确提交后调度前退出',lexicalOnly:true},202,{token}).then(()=>({response:'RECEIVED'}),()=>({response:'UNKNOWN'}));
  for(let attempt=0;attempt<200&&!await exists(held);attempt++)await pause(50);assert.equal(await exists(held),true);
  const method=await readFile(held,'utf8');assert.match(method,/RunServiceImpl\$[^#]+#afterCommit/);
  const rows=(await sql.query(`SELECT r.id,r.status FROM integration_requests q JOIN runs r ON r.id=q.run_id WHERE q.request_id='${requestId}'`)).split('\t');assert.equal(rows.length,2);assert.equal(rows[1],'QUEUED');assert.match(rows[0],/^[a-f0-9-]{36}$/);assert.equal(await modelCalls(),before);
  await run(['kill','--signal','KILL',appContainer.Id]);await writeFile(join(privateDir,'debug-console.log'),JSON.stringify(await debug));await submission;
  await command('node',[join(bundleDir,'ops.mjs'),`--directory=${sourceState.directory}`,'--action=recover-guard',`--request-id=${randomUUID()}`,`--holder-id=${runtime.runtimeId}`,`--holder-container=${appContainer.Id}`]);
  await sourceState.compose(['up','--detach','--wait']);const login=await request(origin,'/api/auth/login',{body:{username:'enterprise_admin',password:'Synthetic!Admin8'}});assert.equal(login.status,200);ctx.setCookie(login.headers['set-cookie'][0].split(';')[0]);
  const terminal=await waitRun(rows[0]);assert.equal(terminal.status,'INTERRUPTED');assert.equal(terminal.errorCode,'PROCESS_RESTARTED');
  assert.equal((await call(`/open-api/v1/requests/${requestId}`,undefined,200,{token})).body.runId,rows[0]);assert.equal(await modelCalls(),before);
  return {faultMethod:method,committedStatus:'QUEUED',runId:rows[0],restartedStatus:'INTERRUPTED',fixedReceiptRetained:true,modelCalls:0,finalJarUnchanged:true,debugPortInternalOnly:true};
 });
}
/** 停写导入已保存固定日志但首次凭据查询被真实数据库锁阻塞时终止维护JVM，再续办同一请求。 */
export async function interruptImport(ctx,args,requestId){
 const {sourceState,privateDir,command}=ctx,sql=suiteSql(sourceState);assert.match(requestId,/^[a-f0-9-]{36}$/);
 let connectionId,locked=false,output='',errorOutput='';
 const child=spawn('docker',['exec',sql.mysql.Id,'sh','-c',sql.shell,'--',"SELECT CONNECTION_ID(); LOCK TABLES credentials WRITE; SELECT 'LOCKED'; DO SLEEP(120); UNLOCK TABLES;"],{stdio:['ignore','pipe','pipe']});
 child.stdout.on('data',bytes=>{output+=bytes.toString();const match=/^(\d+)\s/m.exec(output);if(match)connectionId=Number(match[1]);if(output.includes('LOCKED'))locked=true;});child.stderr.on('data',bytes=>errorOutput+=bytes.toString());
 const ended=new Promise(resolve=>child.once('exit',code=>resolve({code,output,errorOutput})));
 try{
  for(let attempt=0;attempt<200&&!locked;attempt++)await pause(50);assert.equal(locked,true);assert.ok(Number.isInteger(connectionId));
  const execution=command('node',args).then(value=>({ok:true,...value}),error=>({ok:false,stdout:error.stdout||'',stderr:error.stderr||''}));
  const journal=join(sourceState.directory,`maintenance-${requestId}`,'receipt.json.journal');
  for(let attempt=0;attempt<500&&!await exists(journal);attempt++)await pause(50);assert.equal(await exists(journal),true);
  const frozen=JSON.parse(await readFile(journal,'utf8')),before=await readFile(journal,'utf8');
  const ids=(await run(['ps','--quiet','--filter',`label=io.supportops.deployment=${sourceState.metadata.deploymentId}`,'--filter','label=com.docker.compose.service=app'])).stdout.trim().split(/\s+/).filter(Boolean);assert.equal(ids.length,1);
  const maintenance=JSON.parse((await run(['inspect',ids[0]])).stdout)[0];assert.ok(maintenance.Args.includes('-Dloader.main=io.supportops.ops.EnterpriseMaintenance'));assert.equal(maintenance.Image,sourceState.metadata.images.app.id);
  await run(['kill','--signal','KILL',maintenance.Id]);const first=await execution;assert.equal(first.ok,false);await writeFile(join(privateDir,'import-interrupted-console.log'),first.stdout+first.stderr);
  await sql.query('KILL CONNECTION '+connectionId);await writeFile(join(privateDir,'import-lock-console.json'),JSON.stringify(await ended));connectionId=null;
  await command('node',args);assert.equal(await readFile(journal,'utf8'),before);
  const owners=Object.values(frozen.owners);for(const owner of owners){assert.match(owner,/^[a-f0-9-]{36}$/);assert.equal(Number(await sql.query(`SELECT COUNT(*) FROM credentials WHERE owner_id='${owner}'`)),1);}
  return {maintenanceJar:sourceState.metadata.jarSha256,crashAfterJournal:true,sameRequestId:true,journalUnchanged:true,credentialOwners:owners.length,noDuplicateCredentials:true};
 }finally{if(connectionId)await sql.query('KILL CONNECTION '+connectionId).catch(()=>{});}
}
