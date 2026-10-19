import assert from 'node:assert/strict';
import {mkdir,readFile,writeFile,copyFile,chmod,stat,cp,unlink} from 'node:fs/promises';
import {join} from 'node:path';
import {randomUUID,randomBytes} from 'node:crypto';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {createRequire} from 'node:module';
import https from 'node:https';
import {parse,run,deployment,hash,helper} from './runtime.mjs';
import {machineOpenApi} from './machine-openapi.mjs';
import {runFollowupChecks,committedBeforeScheduling,interruptImport} from './followup-acceptance.mjs';
const input=parse(process.argv.slice(2)),base=input.directory,suite=input['suite-id'];
assert.match(suite,/^[a-f0-9-]{36}$/);
const publicDir=join(base,'public'),privateDir=join(base,'private');await mkdir(publicDir);await mkdir(privateDir,{mode:0o700});
const bundleDir=join(base,'bundle');await cp('/bundle',bundleDir,{recursive:true,errorOnExist:true,force:false});
const report={kind:'ENTERPRISE_PACKAGE_ACCEPTANCE_V1',suiteId:suite,status:'RUNNING',checks:[],resources:[],startedAt:new Date().toISOString()};
const execute=promisify(execFile),cert=join(privateDir,'server.p12'),passwordFile=join(privateDir,'tls-password'),trust=join(privateDir,'truststore.p12');
const password=randomBytes(24).toString('base64url');await writeFile(passwordFile,password,{mode:0o600});
const command=async(file,args)=>execute(file,args,{timeout:600000,maxBuffer:2*1024*1024});
const check=async(id,name,operation)=>{try{const detail=await operation();report.checks.push({id,name,status:'PASSED',detail});console.log(JSON.stringify({id,status:'PASSED'}));}catch(error){report.checks.push({id,name,status:'FAILED',reason:/^[A-Z_]+$/.test(error.message)?error.message:'ASSERTION_OR_RUNTIME_FAILURE'});throw error;}};
let sourceState;const initializedDirectories=[];
try{
 await check('EIT-30-INSTALL','校验离线文件并实际加载固定镜像',async()=>{
  await command('sh',[join(bundleDir,'install.sh')]);return {sha256Validated:true,dockerLoadExecuted:true};
 });
 await command('keytool',['-genkeypair','-alias','test','-keyalg','RSA','-keysize','2048','-validity','3','-dname','CN=SupportOps isolated acceptance',
  '-ext','bc=ca:true','-ext','san=ip:127.0.0.1,dns:localhost,dns:source-one,dns:source-two,dns:source-minimal,dns:protocol-model','-keystore',cert,'-storetype','PKCS12','-storepass:file',passwordFile,'-keypass:file',passwordFile]);
 const ca=join(privateDir,'ca.pem');await command('keytool',['-exportcert','-rfc','-alias','test','-keystore',cert,'-storepass:file',passwordFile,'-file',ca]);
 await command('keytool',['-importcert','-noprompt','-alias','acceptance-ca','-file',ca,'-keystore',trust,'-storetype','PKCS12','-storepass:file',passwordFile]);
 const request=async(origin,path,{body,rawBody,token,cookie,headers={}}={})=>new Promise((resolve,reject)=>{
  const bytes=rawBody|| (body===undefined?null:Buffer.from(JSON.stringify(body)));
  const req=https.request(new URL(path,origin),{method:bytes?'POST':'GET',ca:report.ca,servername:'localhost',timeout:15000,
   headers:{...(bytes?{'Content-Type':'application/json','Content-Length':bytes.length,'X-SupportOps-Request':'1'}:{}),...(token?{Authorization:`Bearer ${token}`}:{Cookie:cookie||''}),...headers}},res=>{
   let size=0;const chunks=[];res.on('data',chunk=>{size+=chunk.length;if(size>2*1024*1024)req.destroy(new Error('BODY_TOO_LARGE'));else chunks.push(chunk);});
   res.on('end',()=>{const text=Buffer.concat(chunks).toString('utf8');let value;try{value=JSON.parse(text);}catch{value=null;}resolve({status:res.statusCode,body:value,headers:res.headers});});res.on('error',reject);
  });req.on('timeout',()=>req.destroy(new Error('HTTP_TIMEOUT')));req.on('error',reject);req.end(bytes);
 });
 report.ca=await readFile(ca,'utf8'); // 内存CA不含秘密，报告写入前移除此字段。
 const initialize=async(name,port)=>{
  const path=join(base,name);await command('node',[join(bundleDir,'initialize.mjs'),`--bundle=${bundleDir}`,`--directory=${path}`,`--origin=https://127.0.0.1:${port}`,
   `--tls-p12=${cert}`,`--tls-password-file=${passwordFile}`,`--truststore-p12=${trust}`,`--truststore-password-file=${passwordFile}`]);// 验收环境使用已核对无冲突的10.253网段，避免保留的历史网络耗尽默认池；交付模板不变。
  const networkIds=(await run(['network','ls','--quiet'])).stdout.trim().split(/\s+/).filter(Boolean);
  const networks=JSON.parse((await run(['network','inspect',...networkIds])).stdout);
  const subnet=offset=>{
   for(let third=200+initializedDirectories.length*2+offset;third<256;third+=2){
    const candidate=(10*2**24+253*2**16+third*256)>>>0;
    const conflict=networks.some(network=>(network.IPAM.Config||[]).some(config=>{
     if(!/^\d+\.\d+\.\d+\.\d+\/\d+$/.test(config.Subnet||''))return false;
     const [address,prefix]=config.Subnet.split('/'),parts=address.split('.').map(Number),value=(parts[0]*2**24+parts[1]*2**16+parts[2]*256+parts[3])>>>0,mask=(0xffffffff << (32-Number(prefix)))>>>0;
     return ((candidate&mask)>>>0)===((value&mask)>>>0);
    }));
    if(!conflict)return '10.253.'+third+'.0/24';
   }
   throw new Error('ISOLATED_NETWORK_RANGE_EXHAUSTED');
  };
  const composePath=join(path,'compose.yml'),compose=await readFile(composePath,'utf8'),replacement='networks:\n  storage: {internal: true, ipam: {config: [{subnet: "'+subnet(0)+'"}]}}\n  egress: {ipam: {config: [{subnet: "'+subnet(1)+'"}]}}';
  assert.match(compose,/networks:\r?\n  storage: \{internal: true\}\r?\n  egress: \{\}/);
  await writeFile(composePath,compose.replace(/networks:\r?\n  storage: \{internal: true\}\r?\n  egress: \{\}/,replacement));
  initializedDirectories.push(path);return path;
 };
 const sourceDir=await initialize('source-deployment',18443);
 // 仅专属故障注入部署开放容器内部JDWP，无宿主端口；交付模板和最终JAR保持原字节。
 const originalCompose=await readFile(join(sourceDir,'compose.yml'),'utf8');
 await writeFile(join(sourceDir,'compose.yml'),originalCompose.replace(/    environment:\r?\n/,'    environment:\n      JAVA_TOOL_OPTIONS: "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:18501"\n'));
 report.faultInjection={kind:'INTERNAL_JDWP_AFTER_COMMIT',originalComposeSha256:await hash(join(bundleDir,'compose.yml'))};
 sourceState={directory:sourceDir,compose:async args=>run(['compose','--project-directory',sourceDir,'-f',join(sourceDir,'compose.yml'),...args])};
 await sourceState.compose(['up','--detach','--wait']);sourceState=await deployment(sourceDir);report.resources.push({deploymentId:sourceState.metadata.deploymentId,directory:sourceDir,containers:sourceState.containers.map(value=>value.Id)});
 const origin='https://127.0.0.1:18443';let cookie;
 const call=async(path,body,status=200,extra={})=>{const result=await request(origin,path,{body,cookie,...extra});assert.equal(result.status,status,`${path} status`);return result;};
 await check('EIT-30','离线包身份与内网监听',async()=>{
  assert.equal((await command('node',['--version'])).stdout.trim(),'v22.16.0');
  for(const item of sourceState.containers.filter(value=>value.Config.Labels['com.docker.compose.service']!=='app'))assert.ok(Object.values(item.NetworkSettings.Ports||{}).every(value=>value===null));
  const permissions=(await stat(join(sourceDir,'secrets/master-key.json'))).mode&0o777;assert.equal(permissions,0o600);
  const status=await call('/api/auth/setup');assert.equal(status.body.setupSecretRequired,true);return {deploymentId:sourceState.metadata.deploymentId,images:sourceState.metadata.images};
 });
 await check('EIT-09','HTTPS初始化与来源拒绝',async()=>{
  await call('/api/auth/setup',{username:'enterprise_admin',password:'Synthetic!Admin8'},403,{headers:{'X-SupportOps-Setup-Secret':'wrong'}});
  const setup=await readFile(join(sourceDir,'secrets/setup-secret'),'utf8');const result=await call('/api/auth/setup',{username:'enterprise_admin',password:'Synthetic!Admin8'},200,{headers:{'X-SupportOps-Setup-Secret':setup}});
  cookie=result.headers['set-cookie'][0].split(';')[0];assert.match(result.headers['set-cookie'][0],/Secure/);
  await call('/api/auth/setup',{username:'other_admin',password:'Synthetic!Admin8'},409,{headers:{'X-SupportOps-Setup-Secret':setup}});
  await call('/api/status',undefined,403,{headers:{Origin:'https://other.invalid'}});
  await call('/api/status',undefined,403,{headers:{Host:'other.invalid','X-Forwarded-Proto':'https','X-Forwarded-Host':'127.0.0.1:18443'}});
  const internal=await call('/mcp',undefined,403,{headers:{'X-SupportOps-Internal-Key':'fake'}});assert.equal(internal.status,403);return {setupConsumed:true,cookieSecure:true,spoofedOriginsRejected:true};
 });
 const resource=async(kind,parent,code,name,productKey,productVersion)=> (await call(`/api/enterprise/${kind}`,{requestId:randomUUID(),id:null,parentId:parent,code,name,productKey,productVersion,status:'ACTIVE',expectedVersion:null})).body;
 const project=await resource('projects',null,'isolated-enterprise','隔离企业项目',null,null);
 const environments=[];
 for(const [alias,name]of [['source-one','订单ERP'],['source-two','财务ERP']]){
  const system=await resource('systems',project.id,alias,name,'example-erp',null);const environment=await resource('environments',system.id,'test',`${name}测试`,null,'2.0');
  const secret=randomBytes(32).toString('base64url'),file=join(privateDir,`${alias}.secret`);await writeFile(file,secret,{mode:0o600});
  const network=`supportops-${sourceState.metadata.deploymentId.slice(0,8)}_egress`,container=`so-${suite.slice(0,8)}-${alias}`;
  await run(['run','--detach','--name',container,'--label',`io.supportops.enterprise-tests=${suite}`,'--network',network,'--network-alias',alias,
   '--mount',`type=bind,source=${privateDir},target=/private,readonly`,'--mount',`type=bind,source=${bundleDir}/diagnostic-source.jar,target=/sample.jar,readonly`,
   '--user','0:0','--env',`SOURCE_SYSTEM=${alias}`,'--env','SOURCE_ENVIRONMENT=test','--env','SOURCE_PRODUCT=example-erp','--env','SOURCE_VERSION=2.0',
   '--env',`SOURCE_SECRET_FILE=/private/${alias}.secret`,'--env','SOURCE_PORT=9443','--env','SOURCE_BIND_ADDRESS=0.0.0.0',
   '--env','SOURCE_TLS_KEYSTORE=/private/server.p12','--env','SOURCE_TLS_PASSWORD_FILE=/private/tls-password','--entrypoint','java',sourceState.metadata.images.app.name,'-jar','/sample.jar']);
  const details=JSON.parse((await run(['inspect',container])).stdout)[0],address=details.NetworkSettings.Networks[network].IPAddress;
  report.resources.push({type:'source',id:details.Id,name:container,address});
  let connector=(await call('/api/enterprise/connectors',{requestId:randomUUID(),environmentId:environment.id,type:'STANDARD_REST',config:{baseUrl:`https://${alias}:9443`,allowedAddresses:[address],capabilities:['APP_INFO','EFFECTIVE_CONFIG','RECENT_LOGS','DOWNSTREAM_HEALTH'],modelData:['APP_INFO','DOWNSTREAM_HEALTH'],allowLocalHttp:false},bearerSecret:secret})).body;
  await new Promise(resolve=>setTimeout(resolve,1000));
  connector=(await call('/api/enterprise/connectors/test',{requestId:randomUUID(),revisionId:connector.id,expectedVersion:null})).body;
  assert.equal(connector.testStatus,'PASSED',`${alias} protocol test`);
  await call('/api/enterprise/connectors/enable',{requestId:randomUUID(),revisionId:connector.id,expectedVersion:environment.version});
  environments.push({system,environment,connector,secret,address,alias});
 }
 report.checks.push({id:'EIT-34',name:'两套独立HTTPS Java源无需核心修改',status:'PASSED',detail:{systems:environments.map(value=>value.system.id)}});
 const application=(await call('/api/enterprise/applications',{requestId:randomUUID(),id:null,projectId:project.id,code:'ticket-system',name:'合成工单应用',status:'ACTIVE',permissions:['DIAGNOSE','READ','CANCEL'],environmentIds:environments.map(value=>value.environment.id),expectedVersion:null})).body;
 const issuance=(await call('/api/enterprise/applications/keys',{requestId:randomUUID(),applicationId:application.id,expiresInDays:1,rotate:false})).body;
 const token=issuance.secret;assert.ok(token);
 const machine=await call('/open-api/v1/environments',undefined,200,{token});assert.equal(machine.body.length,2);
 await call('/open-api/v1/environments',undefined,401); // Cookie不会成为机器凭据。
 const appContainer=sourceState.containers.find(value=>value.Config.Labels['com.docker.compose.service']==='app');
 const modelSecret=randomBytes(32).toString('base64url');await writeFile(join(privateDir,'model.secret'),modelSecret,{mode:0o600});
 await copyFile('/source/src/test/e2e/enterprise-model-fixture.mjs',join(privateDir,'model-fixture.mjs'));
 const modelContainer=`so-${suite.slice(0,8)}-model`;
 const modelId=(await run(['run','--detach','--name',modelContainer,'--label',`io.supportops.enterprise-tests=${suite}`,
  '--network',`container:${appContainer.Id}`,'--mount',`type=bind,source=${privateDir},target=/private`,'--user','0:0',
  '--entrypoint','node',sourceState.metadata.images.app.name,'/private/model-fixture.mjs'])).stdout.trim();
 report.resources.push({type:'source',id:modelId,name:modelContainer,kind:'SYNTHETIC_MODEL'});
 const settings=(await call('/api/model-settings')).body;
 await call('/api/model-settings/model',{provider:'custom',model:'protocol-fixture',baseUrl:'http://127.0.0.1:18500/v1',apiKey:modelSecret,keyAction:'replace',revision:settings.revision});
 await check('EIT-27','管理员与普通用户浏览器和响应式布局',async()=>{
  const {chromium}=createRequire('/source/package.json')('@playwright/test');const browser=await chromium.launch({headless:true,args:['--no-sandbox']});
  try{
  const context=await browser.newContext({ignoreHTTPSErrors:true});await context.addCookies([{name:cookie.split('=')[0],value:cookie.split('=')[1],url:origin,secure:true}]);
  const page=await context.newPage();await page.goto(origin);await page.locator('[data-page=enterprise]').click();await page.locator('#enterprise-tree li').first().waitFor();
  const save=async(path,selector)=>{
   const [response]=await Promise.all([page.waitForResponse(value=>value.url().endsWith(path)&&value.request().method()==='POST'),page.locator(selector).click()]);
   assert.equal(response.status(),200);return response.json();
  };
  const createResource=async(kind,parent,code,name)=>{
   await page.locator(`[data-enterprise-create=${kind}]`).click();
   if(parent)await page.locator('#enterprise-parent').selectOption(parent);
   await page.locator('#enterprise-code').fill(code);await page.locator('#enterprise-name').fill(name);
   if(kind==='SYSTEM')await page.locator('#enterprise-product-key').fill('example-erp');
   if(kind==='ENVIRONMENT')await page.locator('#enterprise-product-version').fill('2.0');
   const value=await save(`/api/enterprise/${{PROJECT:'projects',SYSTEM:'systems',ENVIRONMENT:'environments'}[kind]}`,'#enterprise-resource-submit');
   await page.locator('#enterprise-id').evaluate((element,id)=>new Promise(resolve=>{
    if(element.value===id)return resolve();const timer=setInterval(()=>{if(element.value===id){clearInterval(timer);resolve();}},50);
   }),value.id);return value;
  };
  const uiProject=await createResource('PROJECT',null,'browser-install','浏览器接入项目');
  const uiSystem=await createResource('SYSTEM',uiProject.id,'source-one','浏览器订单系统');
  const uiEnvironment=await createResource('ENVIRONMENT',uiSystem.id,'test','浏览器测试环境');
  await page.locator('#enterprise-tab-connections').click();
  await page.locator('#enterprise-base-url').fill('https://source-one:9443');await page.locator('#enterprise-addresses').fill(environments[0].address);
  await page.locator('#enterprise-bearer').fill(environments[0].secret);
  await save('/api/enterprise/connectors','#enterprise-connector-form button[type=submit]');
  await page.locator('#enterprise-connectors').getByRole('button',{name:'测试此修订'}).waitFor();
  const tested=await save('/api/enterprise/connectors/test','#enterprise-connectors button.secondary');assert.equal(tested.testStatus,'PASSED');
  await page.locator('#enterprise-connectors button.primary').waitFor({state:'visible'});
  await save('/api/enterprise/connectors/enable','#enterprise-connectors button.primary');
  const user=(await call('/api/users',{username:'browser_user',password:'Synthetic!User88',role:'USER'})).body;
  await page.reload();await page.locator('[data-page=enterprise]').click();
  await page.locator('#enterprise-tree').getByRole('button',{name:'浏览器测试环境 · 可用',exact:true}).click();
  await page.locator('#enterprise-tab-connections').click();
  await page.locator('#enterprise-member-user').selectOption(user.id);
  await save('/api/enterprise/project-members','#enterprise-member-form button[type=submit]');
  await page.locator('#enterprise-grant-kind').selectOption('ENVIRONMENT');
  await save('/api/enterprise/environment-grants','#enterprise-member-form button[type=submit]');
  for(const width of [1280,960,760]){await page.setViewportSize({width,height:1000});await page.evaluate(()=>window.scrollTo(0,0));assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth),true);await page.screenshot({path:join(publicDir,`enterprise-${width}.png`),fullPage:true});}
  await page.locator('#enterprise-tab-applications').click();
  await page.locator('#enterprise-application-project').focus();assert.equal(await page.locator('#enterprise-application-project').evaluate(element=>element===document.activeElement),true);
  const userContext=await browser.newContext({ignoreHTTPSErrors:true}),userPage=await userContext.newPage();
  await userPage.goto(origin+'/login.html');await userPage.locator('#username').fill('browser_user');await userPage.locator('#password').fill('Synthetic!User88');
  await userPage.locator('#login-submit').click();await userPage.waitForURL(origin+'/');
  await userPage.locator('#diagnosis-environment').selectOption(uiEnvironment.id);
  assert.equal(await userPage.locator('[data-page=enterprise]').isVisible(),false);
  await userPage.locator('#question').fill('读取本系统应用信息，核对证据中的系统和环境身份。');
  await userPage.locator('#start-run').click();
  await userPage.locator('#run-state').filter({hasText:'已完成'}).waitFor({timeout:120000});
  await userPage.screenshot({path:join(publicDir,'enterprise-user-diagnosis.png'),fullPage:true});
  return {widths:[1280,960,760],horizontalOverflow:false,browserCreatedEnvironment:uiEnvironment.id,ordinaryUserDiagnosis:'COMPLETED'};
  }finally{await browser.close();}
 });
 await check('EIT-09-GUARD','第二JVM不能抢占部署',async()=>{
  const result=await sourceState.compose(['run','--rm','--no-deps','app']).then(()=>({exit:0})).catch(error=>({exit:error.code,stdout:(error.stdout||'')+(error.stderr||'')}));
  assert.notEqual(result.exit,0);assert.match(result.stdout,/DEPLOYMENT_ALREADY_RUNNING_OR_UNKNOWN/);return {rejected:true};
 });
 await check('EIT-14','测试过期拒绝激活与重新测试的固定回执',async()=>{
  const value=environments[0],mysql=sourceState.containers.find(item=>item.Config.Labels['com.docker.compose.service']==='mysql');
  assert.match(value.connector.id,/^[a-f0-9-]{36}$/);
  await run(['exec',mysql.Id,'sh','-c',`MYSQL_PWD="$(cat /run/secrets/mysql-root)" mysql -uroot supportops -e "UPDATE connector_tests SET finished_at='2020-01-01T00:00:00Z' WHERE revision_id='${value.connector.id}'"`]);
  const stale=(await call(`/api/enterprise/connectors?environmentId=${value.environment.id}`)).body.find(item=>item.id===value.connector.id);assert.equal(stale.testStatus,'STALE');
  const environment=(await call('/api/enterprise/resources')).body.find(item=>item.id===value.environment.id);
  await call('/api/enterprise/connectors/enable',{requestId:randomUUID(),revisionId:value.connector.id,expectedVersion:environment.version},409);
  const input={requestId:randomUUID(),revisionId:value.connector.id};
  const tested=(await call('/api/enterprise/connectors/test',input)).body;assert.equal(tested.testStatus,'PASSED');
  const replay=(await call('/api/enterprise/connectors/test',input)).body;assert.equal(replay.testedAt,tested.testedAt);
  await call('/api/enterprise/connectors/enable',{requestId:randomUUID(),revisionId:value.connector.id,expectedVersion:environment.version});
  return {staleActivationRejected:true,retested:true,fixedTestReceipt:true};
 });
 const waitRun=async(id)=>{
  for(let attempt=0;attempt<120;attempt++){
   const value=(await call(`/open-api/v1/diagnoses/${id}`,undefined,200,{token})).body;
   if(!['QUEUED','RUNNING'].includes(value.status))return value;
   await new Promise(resolve=>setTimeout(resolve,500));
  }throw new Error('RUN_TERMINAL_TIMEOUT');
 };
 const modelCalls=async()=>JSON.parse(await readFile(join(privateDir,'model-calls.json'),'utf8')).calls;
 const waitCalls=async(expected)=>{for(let attempt=0;attempt<100;attempt++){if(await modelCalls()>=expected)return;await new Promise(resolve=>setTimeout(resolve,100));}throw Error('MODEL_CALL_TIMEOUT');};
 const newApplication=async(code,environmentIds,extra={})=> (await call('/api/enterprise/applications',{
  requestId:randomUUID(),projectId:project.id,code,name:code,status:'ACTIVE',permissions:['DIAGNOSE','READ','CANCEL'],environmentIds,...extra})).body;
 const newKey=async(value,rotate=false,expiresInDays=1)=> (await call('/api/enterprise/applications/keys',{
  requestId:randomUUID(),applicationId:value.id,expiresInDays,rotate})).body;
 await check('EIT-08','一次性签发重放、轮换、密钥上限与撤销',async()=>{
  const value=await newApplication('key-boundary',[environments[0].environment.id]);
  const input={requestId:randomUUID(),applicationId:value.id,expiresInDays:90,rotate:false};
  const first=(await call('/api/enterprise/applications/keys',input)).body;
  const replay=(await call('/api/enterprise/applications/keys',input)).body;assert.equal(replay.secret,undefined);assert.equal(replay.key.id,first.key.id);
  const second=await newKey(value,true,90);assert.ok(second.secret);
  const keys=(await call(`/api/enterprise/applications/keys?applicationId=${value.id}`)).body;
  assert.ok(Date.parse(keys.find(key=>key.id===first.key.id).expiresAt)<=Date.now()+86401000);
  await call('/api/enterprise/applications/keys',{requestId:randomUUID(),applicationId:value.id,expiresInDays:1,rotate:true},409);
  await call('/api/enterprise/applications/keys/revoke',{requestId:randomUUID(),keyId:first.key.id});
  await call('/open-api/v1/environments',undefined,401,{token:first.secret});
  const mysql=sourceState.containers.find(value=>value.Config.Labels['com.docker.compose.service']==='mysql');
  assert.match(second.key.id,/^[a-f0-9-]{36}$/);
  await run(['exec',mysql.Id,'sh','-c',`MYSQL_PWD="$(cat /run/secrets/mysql-root)" mysql -uroot supportops -e "UPDATE application_access_keys SET expires_at='2020-01-01T00:00:00Z' WHERE id='${second.key.id}'"`]);
  await call('/open-api/v1/environments',undefined,401,{token:second.secret});
  return {oneTimeReplayContainsNoSecret:true,rotationOverlapHours:24,keyLimit:2,revokedRejected:true,expiredRejected:true};
 });
 await check('EIT-15-21-23','双环境并发、同键归属、容量拒绝和取消',async()=>{
  const other=await newApplication('second-caller',[environments[1].environment.id]),otherToken=(await newKey(other)).secret;
  const requestId=randomUUID(),before=await modelCalls();await writeFile(join(privateDir,'model.pause'),'pause',{mode:0o600});
  try{
   const input={requestId,environmentId:environments[0].environment.id,question:'合成并发检查',lexicalOnly:true};
   const replies=await Promise.all([request(origin,'/open-api/v1/diagnoses',{token,body:input}),request(origin,'/open-api/v1/diagnoses',{token,body:input})]);
   assert.deepEqual(replies.map(value=>value.status).sort(),[200,202]);assert.equal(replies[0].body.runId,replies[1].body.runId);
   const second=(await call('/open-api/v1/diagnoses',{...input,environmentId:environments[1].environment.id},202,{token:otherToken})).body;
   assert.notEqual(second.runId,replies[0].body.runId);await waitCalls(before+2);
   const rejectedId=randomUUID();await call('/open-api/v1/diagnoses',{...input,requestId:rejectedId},429,{token});
   await call(`/open-api/v1/requests/${rejectedId}`,undefined,404,{token});
   await call(`/open-api/v1/diagnoses/${second.runId}`,undefined,404,{token});
   // 运行期间可以保存并测试草稿，但激活仍需环境空闲。
   const draft=(await call('/api/enterprise/connectors',{requestId:randomUUID(),environmentId:environments[0].environment.id,type:'STANDARD_REST',
    config:{baseUrl:'https://source-one:9443',allowedAddresses:[environments[0].address],capabilities:['APP_INFO'],modelData:['APP_INFO'],allowLocalHttp:false},
    secretAction:'KEEP',credentialSourceRevisionId:environments[0].connector.id})).body;
   assert.equal((await call('/api/enterprise/connectors/test',{requestId:randomUUID(),revisionId:draft.id})).body.testStatus,'PASSED');
   const state=(await call('/api/enterprise/resources')).body.find(value=>value.id===environments[0].environment.id);
   await call('/api/enterprise/connectors/enable',{requestId:randomUUID(),revisionId:draft.id,expectedVersion:state.version},409);
   const current=(await call('/api/model-settings')).body;
   await call('/api/model-settings/model',{provider:'custom',model:'protocol-fixture',baseUrl:'http://127.0.0.1:18500/v1',keyAction:'keep',revision:current.revision},409);
   await call(`/open-api/v1/diagnoses/${replies[0].body.runId}/cancel`,{},200,{token});
   await call(`/open-api/v1/diagnoses/${second.runId}/cancel`,{},200,{token:otherToken});
   assert.equal((await waitRun(replies[0].body.runId)).status,'CANCELLED');
   const cancelled=(await call(`/open-api/v1/diagnoses/${second.runId}`,undefined,200,{token:otherToken})).body;assert.equal(cancelled.status,'CANCELLED');
   return {parallelEnvironments:2,concurrentSameKeyOneRun:true,sameKeyAcrossAppsDistinct:true,rejectedReceiptAbsent:true,cancelled:2,draftAllowed:true,activationAndGlobalChangesWhileBusyRejected:true};
  }finally{await unlink(join(privateDir,'model.pause')).catch(()=>{});}
 });
 await check('EIT-23-RATE','应用读取频率限制带重试时间',async()=>{
  const value=await newApplication('rate-boundary',[environments[0].environment.id],{readPerMinute:1}),key=(await newKey(value)).secret;
  await call('/open-api/v1/environments',undefined,200,{token:key});
  const denied=await call('/open-api/v1/environments',undefined,429,{token:key});assert.equal(denied.headers['retry-after'],'60');return {httpStatus:429,retryAfter:60};
 });
 await check('EIT-24','运行中撤销应用阻止后续工具和模型调用',async()=>{
  const value=await newApplication('revocation-boundary',[environments[0].environment.id]),key=(await newKey(value)).secret,before=await modelCalls();
  await writeFile(join(privateDir,'model.pause'),'pause',{mode:0o600});
  const accepted=(await call('/open-api/v1/diagnoses',{requestId:randomUUID(),environmentId:environments[0].environment.id,question:'合成撤销检查',lexicalOnly:true},202,{token:key})).body;
  await waitCalls(before+1);
  await call('/api/enterprise/applications',{requestId:randomUUID(),id:value.id,projectId:value.projectId,code:value.code,name:value.name,
   permissions:value.permissions,environmentIds:value.environmentIds,status:'DISABLED',expectedVersion:value.version});
  await unlink(join(privateDir,'model.pause'));
  let terminal;
  for(let attempt=0;attempt<100;attempt++){terminal=(await call(`/api/runs/${accepted.runId}`)).body;if(!['QUEUED','RUNNING'].includes(terminal.status))break;await new Promise(resolve=>setTimeout(resolve,100));}
  assert.equal(terminal.status,'FAILED');assert.equal(terminal.errorCode,'AUTHORIZATION_REVOKED');assert.equal(await modelCalls(),before+1);
  await call(`/open-api/v1/diagnoses/${accepted.runId}`,undefined,401,{token:key});return {errorCode:terminal.errorCode,subsequentModelCalls:0};
 });
 await check('EIT-34-DIAGNOSIS','第二独立系统诊断来源',async()=>{
  const accepted=(await call('/open-api/v1/diagnoses',{requestId:randomUUID(),environmentId:environments[1].environment.id,
   question:'读取本系统应用信息并核对身份来源。',lexicalOnly:true},202,{token})).body;
  const terminal=await waitRun(accepted.runId);assert.equal(terminal.status,'COMPLETED');
  const events=(await call(`/open-api/v1/diagnoses/${accepted.runId}/events?limit=100`,undefined,200,{token})).body.events;
  assert.ok(JSON.stringify(events).includes('source-two'));assert.ok(JSON.stringify(events).includes('SOURCE_EVIDENCE'));
  return {runId:accepted.runId,systemId:environments[1].system.id,status:terminal.status};
 });
 await check('EIT-22-CLIENT','独立客户端实际受理、分页和重复运行恢复',async()=>{
  const secretFile=join(privateDir,'client-key'),stateFile=join(privateDir,'client-request.json');await writeFile(secretFile,token,{mode:0o600});
  const env={...process.env,NODE_EXTRA_CA_CERTS:ca,SUPPORTOPS_API_ORIGIN:origin,SUPPORTOPS_APPLICATION_SECRET_FILE:secretFile,
   SUPPORTOPS_CLIENT_STATE:stateFile,SUPPORTOPS_ENVIRONMENT_ID:environments[1].environment.id,SUPPORTOPS_QUESTION:'独立客户端合成诊断'};
  await execute('node',[join(bundleDir,'examples/client/diagnosis-client.mjs')],{env,timeout:180000,maxBuffer:65536});
  const before=await modelCalls(),first=JSON.parse(await readFile(stateFile,'utf8'));
  await execute('node',[join(bundleDir,'examples/client/diagnosis-client.mjs')],{env,timeout:180000,maxBuffer:65536});
  const replay=JSON.parse(await readFile(stateFile,'utf8'));assert.equal(replay.requestId,first.requestId);assert.equal(await modelCalls(),before);
  assert.ok(!JSON.stringify(first).includes(token));return {sameRequestId:true,noSecondModelExecution:true,realHttps:true};
 });
 await runFollowupChecks({check,call,request,origin,privateDir,publicDir,sourceState,environments,token,newApplication,newKey,modelCalls,waitCalls,waitRun,appContainer,suite,resources:report.resources,getCookie:()=>cookie});
 let storedDocument;
 await check('EIT-20-STORAGE','系统资料原件和关键词索引',async()=>{
  const boundary=`fixture-${randomUUID()}`,fields={systemId:environments[1].system.id,title:'第二系统恢复验收',version:'2.0',requestKey:randomUUID()};
  const parts=Object.entries(fields).map(([name,value])=>`--${boundary}\r\nContent-Disposition: form-data; name="${name}"\r\n\r\n${value}\r\n`);
  parts.push(`--${boundary}\r\nContent-Disposition: form-data; name="file"; filename="restore.md"\r\nContent-Type: text/markdown\r\n\r\n# 第二系统\n恢复索引 restorecanary 对应财务ERP。\r\n--${boundary}--\r\n`);
  const uploaded=await request(origin,'/api/documents/uploads',{cookie,rawBody:Buffer.from(parts.join('')),headers:{'Content-Type':`multipart/form-data; boundary=${boundary}`}});
  assert.equal(uploaded.status,202);storedDocument=uploaded.body;
  for(let attempt=0;attempt<120;attempt++){
   const value=(await call(`/api/documents/uploads/${storedDocument.id}`)).body;
   if(value.status==='SUCCEEDED'){storedDocument=value;break;}
   assert.notEqual(value.status,'FAILED');await new Promise(resolve=>setTimeout(resolve,500));
  }
  for(let attempt=0;attempt<120;attempt++){
   const detail=(await call(`/api/documents/${storedDocument.documentId}`)).body;
   assert.notEqual(detail.document.processingStatus,'FAILED');assert.notEqual(detail.document.textStatus,'FAILED');
   if(detail.document.processingStatus==='READY'&&detail.document.textStatus==='SUCCEEDED')break;
   await new Promise(resolve=>setTimeout(resolve,500));
  }
  const search=(await call(`/api/documents/search?query=restorecanary&version=2.0&lexicalOnly=true&systemId=${environments[1].system.id}`)).body;
  await writeFile(join(privateDir,'upload-search.json'),JSON.stringify({upload:storedDocument,search}),{mode:0o600});
  assert.ok(search.passages.some(value=>value.documentId===storedDocument.documentId));return {documentId:storedDocument.documentId,versionId:storedDocument.versionId};
 });
 await check('EIT-18-VECTOR','实际向量协议和ES索引的系统与版本范围',async()=>{
  const settings=(await call('/api/model-settings')).body;
  await call('/api/model-settings/embedding',{provider:'custom',model:'protocol-vector',baseUrl:'http://127.0.0.1:18500/v1',apiKey:modelSecret,keyAction:'replace',revision:settings.revision});
  for(const value of environments){
   const policy=(await call(`/api/enterprise/data-policy?systemId=${value.system.id}`)).body;
   await call('/api/enterprise/data-policy',{requestId:randomUUID(),systemId:value.system.id,chatAllowed:false,embeddingAllowed:true,expectedVersion:policy.version});
  }
  const boundary=`fixture-${randomUUID()}`,fields={systemId:environments[0].system.id,title:'第一系统同版本',version:'2.0',requestKey:randomUUID()};
  const parts=Object.entries(fields).map(([name,value])=>`--${boundary}\r\nContent-Disposition: form-data; name="${name}"\r\n\r\n${value}\r\n`);
  parts.push(`--${boundary}\r\nContent-Disposition: form-data; name="file"; filename="other.md"\r\nContent-Type: text/markdown\r\n\r\n# 第一系统\nrestorecanary 只适用于订单ERP。\r\n--${boundary}--\r\n`);
  const response=await request(origin,'/api/documents/uploads',{cookie,rawBody:Buffer.from(parts.join('')),headers:{'Content-Type':`multipart/form-data; boundary=${boundary}`}});assert.equal(response.status,202);
  let other;
  for(let attempt=0;attempt<120;attempt++){other=(await call(`/api/documents/uploads/${response.body.id}`)).body;assert.notEqual(other.status,'FAILED');if(other.status==='SUCCEEDED')break;await new Promise(resolve=>setTimeout(resolve,500));}
  for(let attempt=0;attempt<120;attempt++){
   const detail=(await call(`/api/documents/${other.documentId}`)).body;
   assert.notEqual(detail.document.textStatus,'FAILED');
   if(detail.document.textStatus==='SUCCEEDED')break;
   await new Promise(resolve=>setTimeout(resolve,500));
  }
  for(const value of [other,storedDocument])await call(`/api/documents/${value.documentId}/index`,{});
  for(const [system,document]of [[environments[0].system.id,other],[environments[1].system.id,storedDocument]]){
   let result;
   for(let attempt=0;attempt<120;attempt++){result=(await call(`/api/documents/search?query=restorecanary&version=2.0&lexicalOnly=false&systemId=${system}`)).body;if(result.mode==='HYBRID')break;await new Promise(resolve=>setTimeout(resolve,500));}
   assert.equal(result.mode,'HYBRID');assert.ok(result.passages.length>0);assert.ok(result.passages.every(value=>value.documentId===document.documentId));
   const wrong=(await call(`/api/documents/search?query=restorecanary&version=3.0&lexicalOnly=false&systemId=${system}`)).body;assert.equal(wrong.passages.length,0);
  }
  const before=JSON.parse(await readFile(join(privateDir,'model-calls.json'),'utf8')).embeddingCalls,policy=(await call(`/api/enterprise/data-policy?systemId=${environments[0].system.id}`)).body;
  await writeFile(join(privateDir,'embedding.pause'),'pause',{mode:0o600});
  const pending=request(origin,`/api/documents/search?query=restorecanary&version=2.0&lexicalOnly=false&systemId=${environments[0].system.id}`,{cookie});
  for(let attempt=0;attempt<100&&JSON.parse(await readFile(join(privateDir,'model-calls.json'),'utf8')).embeddingCalls===before;attempt++)await new Promise(resolve=>setTimeout(resolve,100));
  assert.equal(JSON.parse(await readFile(join(privateDir,'model-calls.json'),'utf8')).embeddingCalls,before+1);
  await call('/api/enterprise/data-policy',{requestId:randomUUID(),systemId:environments[0].system.id,chatAllowed:false,embeddingAllowed:false,expectedVersion:policy.version});
  await unlink(join(privateDir,'embedding.pause'));
  const denied=await pending;assert.equal(denied.status,403);assert.equal(denied.body.error,'EMBEDDING_POLICY_DENIED');
  await call(`/api/documents/search?query=restorecanary&version=2.0&lexicalOnly=false&systemId=${environments[0].system.id}`,undefined,403);
  assert.equal(JSON.parse(await readFile(join(privateDir,'model-calls.json'),'utf8')).embeddingCalls,before+1);
  return {embeddingProvider:'SYNTHETIC_HTTP_PROTOCOL',storage:'REAL_MYSQL_MINIO_ES',systemsSeparated:2,wrongVersionEmpty:true,inflightRevocationRejected:true,revokedVectorCalls:0};
 });
 await check('EIT-29','应用用量与总量的独立SQL核对',async()=>{
  const day=new Date().toISOString().slice(0,10),usage=(await call(`/api/usage?from=${day}&to=${day}`)).body;
  const mysql=sourceState.containers.find(value=>value.Config.Labels['com.docker.compose.service']==='mysql');
  const sql=`SELECT COUNT(*),COALESCE(SUM(input_tokens),0),COALESCE(SUM(output_tokens),0) FROM runs WHERE SUBSTRING(created_at,1,10)='${day}'`;
  const row=(await run(['exec',mysql.Id,'sh','-c',`MYSQL_PWD="$(cat /run/secrets/mysql-root)" mysql --batch --skip-column-names -uroot supportops -e "${sql}"`])).stdout.trim().split('\t').map(Number);
  assert.deepEqual([usage.summary.runs,usage.summary.inputTokens,usage.summary.outputTokens],row);
  assert.ok(usage.applications.some(value=>value.groupId===application.id));
  assert.ok(usage.applications.reduce((sum,value)=>sum+value.runs,0)<usage.summary.runs);
  return {independentSqlMatched:true,applicationSeparateFromUser:true,totalRuns:row[0],inputTokens:row[1],outputTokens:row[2]};
 });
 await check('EIT-33','公开响应、日志和OpenAPI中的秘密扫描',async()=>{
  const schema=(await call('/v3/api-docs')).body;
  for(const path of ['/open-api/v1/diagnoses','/api/enterprise/connectors','/api/enterprise/applications'])assert.ok(schema.paths[path]);
  for(const path of Object.keys(schema.paths))assert.ok(Object.keys(schema.paths[path]).every(method=>['get','post','parameters'].includes(method)));
  await writeFile(join(publicDir,'machine-openapi.json'),JSON.stringify(machineOpenApi(schema),null,2));
  const values=await Promise.all(['/api/model-settings','/api/enterprise/applications','/api/enterprise/resources',`/api/enterprise/connectors?environmentId=${environments[0].environment.id}`].map(async path=>JSON.stringify((await call(path)).body)));
  const app=sourceState.containers.find(value=>value.Config.Labels['com.docker.compose.service']==='app');
  const logs=await run(['logs',app.Id]);values.push(logs.stdout,logs.stderr,JSON.stringify(schema));
  for(const secret of [token,modelSecret,...environments.map(value=>value.secret)])for(const value of values)assert.ok(!value.includes(secret));
  return {secretMatches:0,generatedOpenApi:true,methods:'GET_POST_ONLY'};
 });
 await check('EIT-17','执行中退出、guard证明恢复与零重跑',async()=>{
  const runtime=(await call('/api/ops/runtime')).body,requestId=randomUUID();
  await writeFile(join(privateDir,'model.pause'),'pause',{mode:0o600});
  const before=JSON.parse(await readFile(join(privateDir,'model-calls.json'),'utf8')).calls;
  const accepted=(await call('/open-api/v1/diagnoses',{requestId,environmentId:environments[1].environment.id,question:'重启中断合成任务',lexicalOnly:true},202,{token})).body;
  for(let attempt=0;attempt<100&&JSON.parse(await readFile(join(privateDir,'model-calls.json'),'utf8')).calls===before;attempt++)await new Promise(resolve=>setTimeout(resolve,100));
  assert.equal(JSON.parse(await readFile(join(privateDir,'model-calls.json'),'utf8')).calls,before+1);
  await run(['kill','--signal','KILL',appContainer.Id]);await unlink(join(privateDir,'model.pause'));
  await command('node',[join(bundleDir,'ops.mjs'),`--directory=${sourceDir}`,'--action=recover-guard',`--request-id=${randomUUID()}`,
   `--holder-id=${runtime.runtimeId}`,`--holder-container=${appContainer.Id}`]);
  await sourceState.compose(['up','--detach','--wait']);
  const login=await request(origin,'/api/auth/login',{body:{username:'enterprise_admin',password:'Synthetic!Admin8'}});assert.equal(login.status,200);cookie=login.headers['set-cookie'][0].split(';')[0];
  const terminal=await waitRun(accepted.runId);assert.equal(terminal.status,'INTERRUPTED');assert.equal(terminal.errorCode,'PROCESS_RESTARTED');
  const receipt=(await call(`/open-api/v1/requests/${requestId}`,undefined,200,{token})).body;assert.equal(receipt.runId,accepted.runId);
  assert.equal(JSON.parse(await readFile(join(privateDir,'model-calls.json'),'utf8')).calls,before+1);
  return {runId:accepted.runId,receiptRetained:true,modelCallsAfterRestart:0,guardRecoveredWithStoppedContainerProof:true};
 });
 await committedBeforeScheduling({check,call,request,origin,privateDir,sourceState,environments,token,modelCalls,waitRun,appContainer,bundleDir,command,setCookie:value=>{cookie=value;}});
 await check('EIT-07','停写导入、同键续办与主密钥轮换',async()=>{
  await sourceState.compose(['stop','--timeout','30','app']);
  const legacy=join(privateDir,'legacy-settings.json');await writeFile(legacy,JSON.stringify({format:1,roles:{model:{provider:'custom',name:'protocol-fixture','base-url':'http://127.0.0.1:18500/v1','api-key':modelSecret}}}),{mode:0o600});
  const original=await hash(legacy),requestId=randomUUID(),args=[join(bundleDir,'ops.mjs'),`--directory=${sourceDir}`,'--action=import-model',`--request-id=${requestId}`,`--source-file=${legacy}`,'--target-file=imported-model.json'];
  const interruption=await interruptImport({sourceState,privateDir,command},args,requestId);const replay=await command('node',args);assert.ok(replay.stdout.includes('"replayed":true'));assert.equal(await hash(legacy),original);
  const imported=(await helper(sourceState,[`type=volume,source=${sourceState.volumeIds['application-data'].name},target=/data,readonly`],'cat',['/data/imported-model.json'])).stdout;
  assert.equal(JSON.parse(imported).format,2);assert.ok(!imported.includes(modelSecret));
  const ringFile=join(sourceDir,'secrets/master-key.json'),ring=JSON.parse(await readFile(ringFile,'utf8')),newId=`test_${randomUUID().replaceAll('-','')}`;
  ring.keys[newId]=randomBytes(32).toString('base64');ring.activeKeyId=newId;await writeFile(ringFile,JSON.stringify(ring),{mode:0o600});
  await command('node',[join(bundleDir,'ops.mjs'),`--directory=${sourceDir}`,'--action=rotate',`--request-id=${randomUUID()}`]);
  ring.keys={[newId]:ring.keys[newId]};await writeFile(ringFile,JSON.stringify(ring),{mode:0o600});
  await sourceState.compose(['up','--detach','--wait']);
  const login=await request(origin,'/api/auth/login',{body:{username:'enterprise_admin',password:'Synthetic!Admin8'}});assert.equal(login.status,200);cookie=login.headers['set-cookie'][0].split(';')[0];
  assert.equal((await call('/api/model-settings')).body.model.configured,true);
  return {sourceUnchanged:true,importReplay:true,interruption,format:2,oldKeyRemoved:true,decryptConfigurationAfterRotation:true};
 });
 await check('EIT-32','主密钥分离的冷备份与新目标恢复',async()=>{
  const backupDir=join(base,'backup');await command('node',[join(bundleDir,'backup.mjs'),`--directory=${sourceDir}`,`--output=${backupDir}`]);
  const manifest=JSON.parse(await readFile(join(backupDir,'manifest.json'),'utf8'));assert.equal(manifest.masterKeyIncluded,false);
  assert.ok(!Object.keys(manifest.files).some(value=>/master|setup|tls|server/.test(value)));
  const targetDir=await initialize('restored-deployment',18444);
  await command('node',[join(bundleDir,'restore.mjs'),`--directory=${targetDir}`,`--backup=${backupDir}`,`--source-master-key-file=${join(sourceDir,'secrets/master-key.json')}`]);
  const target=await deployment(targetDir);report.resources.push({deploymentId:target.metadata.deploymentId,directory:targetDir,containers:target.containers.map(value=>value.Id)});
  await target.compose(['up','--detach','--wait']);const restoredOrigin='https://127.0.0.1:18444';
  const login=await request(restoredOrigin,'/api/auth/login',{body:{username:'enterprise_admin',password:'Synthetic!Admin8'}});assert.equal(login.status,200);
  const restoredCookie=login.headers['set-cookie'][0].split(';')[0];
  const denied=await request(restoredOrigin,'/open-api/v1/environments',{token});assert.equal(denied.status,401);
  const targets=await request(restoredOrigin,'/api/diagnosis-targets',{cookie:restoredCookie});assert.equal(targets.status,200);assert.equal(targets.body.length,0);
  const routes=await request(restoredOrigin,'/api/model-settings',{cookie:restoredCookie});assert.equal(routes.status,200);assert.equal(routes.body.model.configured,false);
  const staleIssue=await request(restoredOrigin,'/api/enterprise/applications/keys',{cookie:restoredCookie,body:{requestId:randomUUID(),applicationId:application.id,expiresInDays:1,rotate:false}});
  assert.equal(staleIssue.status,409);assert.equal(staleIssue.body.error,'APPLICATION_DISABLED');
  const search=await request(restoredOrigin,`/api/documents/search?query=restorecanary&version=2.0&lexicalOnly=true&systemId=${environments[1].system.id}`,{cookie:restoredCookie});
  assert.equal(search.status,200);assert.ok(search.body.passages.some(value=>value.documentId===storedDocument.documentId));
  const original=await request(restoredOrigin,`/api/documents/versions/${storedDocument.versionId}/original`,{cookie:restoredCookie});assert.equal(original.status,200);
  await target.compose(['stop','--timeout','30']);return {sourceDeploymentId:sourceState.metadata.deploymentId,targetDeploymentId:target.metadata.deploymentId,oldApplicationKeyRejected:true,targetsRequireApproval:true,credentials:'REENCRYPTED'};
 });
 await check('EIT-32-NO-KEY','缺源主密钥保留历史但拒绝现场秘密',async()=>{
  const targetDir=await initialize('restored-without-key',18445),before=JSON.parse(await readFile(join(privateDir,'model-calls.json'),'utf8')).calls;
  await command('node',[join(bundleDir,'restore.mjs'),`--directory=${targetDir}`,`--backup=${join(base,'backup')}`]);
  const target=await deployment(targetDir);report.resources.push({deploymentId:target.metadata.deploymentId,directory:targetDir,containers:target.containers.map(value=>value.Id)});
  await target.compose(['up','--detach','--wait']);const targetOrigin='https://127.0.0.1:18445';
  const login=await request(targetOrigin,'/api/auth/login',{body:{username:'enterprise_admin',password:'Synthetic!Admin8'}});assert.equal(login.status,200);
  const targetCookie=login.headers['set-cookie'][0].split(';')[0];
  const targets=await request(targetOrigin,'/api/diagnosis-targets',{cookie:targetCookie});assert.equal(targets.status,200);assert.equal(targets.body.length,0);
  const test=await request(targetOrigin,'/api/enterprise/connectors/test',{cookie:targetCookie,body:{requestId:randomUUID(),revisionId:environments[0].connector.id,expectedVersion:null}});
  assert.equal(test.status,200);assert.equal(test.body.testStatus,'FAILED');assert.ok(/CREDENTIAL|MASTER_KEY/.test(test.body.errorCode));
  assert.equal(JSON.parse(await readFile(join(privateDir,'model-calls.json'),'utf8')).calls,before);
  const original=await request(targetOrigin,`/api/documents/versions/${storedDocument.versionId}/original`,{cookie:targetCookie});assert.equal(original.status,200);
  await target.compose(['stop','--timeout','30']);return {missingSourceKey:'EXPLICIT_FAILURE',targets:0,automaticModelCalls:0,originalRetained:true};
 });
 report.status='PASSED';
}catch(error){report.status='FAILED_RETAINED';await writeFile(join(privateDir,'failure.txt'),error.stack||String(error));console.log(JSON.stringify({status:report.status,reason:/^[A-Z_]+$/.test(error.message)?error.message:'ASSERTION_OR_RUNTIME_FAILURE'}));}
finally{
 if(sourceState)await sourceState.compose(['stop','--timeout','30']).catch(()=>{});
 for(const directory of initializedDirectories){
  const metadata=JSON.parse(await readFile(join(directory,'deployment.json'),'utf8'));
  const ids=(await run(['ps','--quiet','--filter',`label=io.supportops.deployment=${metadata.deploymentId}`])).stdout.trim().split(/\s+/).filter(Boolean);
  if(ids.length){
   const containers=JSON.parse((await run(['inspect',...ids])).stdout);
   if(containers.every(value=>value.Config.Labels?.['io.supportops.deployment']===metadata.deploymentId))await run(['stop','--timeout','30',...ids]);
  }
 }
 for (const resource of report.resources.filter(value=>value.type==='source')) {
  const identity=JSON.parse((await run(['inspect',resource.id])).stdout)[0];
  if(identity.Config.Labels?.['io.supportops.enterprise-tests']===suite)await run(['stop','--time','10',resource.id]);
 }
 delete report.ca;report.finishedAt=new Date().toISOString();await writeFile(join(publicDir,'report.json'),JSON.stringify(report,null,2));
}
if(report.status!=='PASSED')process.exitCode=1;
