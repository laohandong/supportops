import assert from 'node:assert/strict';
import {resolve,join} from 'node:path';
import {fileURLToPath,pathToFileURL} from 'node:url';
import {randomUUID} from 'node:crypto';
import {readFile,writeFile,mkdir} from 'node:fs/promises';
import {createWriteStream} from 'node:fs';
import {unzipSync,zipSync,strToU8} from 'fflate';
import {outcome,draft} from '../src/test/js/fixtures/legacy-outcome.mjs';
import {options,privateDirectory,atomicJson,digest,hash,until} from './reliability/common.mjs';
import {createDeployment,registerRuntime,cleanDeployment} from './reliability/deployment.mjs';
import {launch} from './reliability/runtime.mjs';
import {connection,query,closeDatabase,databaseSnapshot} from './reliability/storage.mjs';
import {backupPlan,makeBackup,restoreBackup} from './reliability/backup-v1.mjs';
import {loadLocalConfig} from './local-config.mjs';
import {systemEnvironment} from './lib/runtime-safety.mjs';
import {capacityCheck} from './reliability/capacity.mjs';
import {suspendAfterBackup} from './reliability/cleanup.mjs';

const root=resolve(fileURLToPath(new URL('..',import.meta.url)));
/** 真正旧JAR建V11库、封存完整备份，再向全新目标恢复并启动新JAR；禁止借用旧任务存储。 */
export async function upgradeChecks(input) {
  const directory=resolve(input.directory),legacyJar=resolve(input['legacy-jar']),jar=resolve(input.jar || join(root,'target/supportops-0.1.0-SNAPSHOT.jar'));
  await privateDirectory(directory);await mkdir(join(directory,'public'));await mkdir(join(directory,'private'));
  const report={schemaVersion:1,startedAt:new Date().toISOString(),status:'RUNNING',checks:[{id:'EIT-01',status:'NOT_RUN'},{id:'EIT-31',status:'NOT_RUN'}],resources:[]};
  const resources=[];let runtime,database,base;
  const account={username:'oc_legacy_admin',password:'Synthetic-Legacy-'+randomUUID()};
  const start=async(deployment,program)=>{
    const env=await loadLocalConfig(deployment.config,systemEnvironment());Object.assign(env,{SUPPORTOPS_MODEL_PROVIDER:'custom',SUPPORTOPS_API_KEY:'',SUPPORTOPS_EMBEDDING_PROVIDER:'disabled',
      SUPPORTOPS_FEISHU_ENABLED:'false',SUPPORTOPS_EVALUATION_ENABLED:'false'});
    await writeFile(deployment.config,Object.entries(env).filter(([key])=>key.startsWith('SUPPORTOPS_')).map(([key,value])=>key+'='+value).join('\n')+'\n',{mode:0o600});
    runtime=await launch({root,env,jar:program,runtimeRoot:deployment.runtimeRoot,cwd:deployment.directory,settings:deployment.settings,providers:'',port:0,
      deployment:join(deployment.directory,'deployment.json'),applicationArgs:['--spring.config.location=classpath:application.yml'],log:createWriteStream(join(directory,'private/application-'+randomUUID()+'.log'))});
    await registerRuntime(deployment,runtime);base=runtime.base;
  };
  const login=async()=>{
    const response=await fetch(base+'/api/auth/login',{method:'POST',headers:{'Content-Type':'application/json','X-SupportOps-Request':'1'},body:JSON.stringify(account)});assert.equal(response.status,200);const cookie=response.headers.get('set-cookie').split(';')[0];
    return async(method,path,body,expected=200)=>{const response=await fetch(base+'/api'+path,{method,headers:{Cookie:cookie,'X-SupportOps-Request':'1',...(body?{'Content-Type':'application/json'}:{})},...(body?{body:JSON.stringify(body)}:{})});
      const text=await response.text();assert.equal(response.status,expected,method+' '+path+' '+text.slice(0,200));return text?JSON.parse(text):null;};
  };
  try {
    const entries=unzipSync(await readFile(legacyJar),{filter:item=>/BOOT-INF\/classes\/db\/migration\/[^/]+\.sql$/.test(item.name)});
    assert.equal(Object.keys(entries).length,9);assert.ok(!Object.keys(entries).some(name=>name.includes('V12')));
    const migrationHashes={};for(const [path,bytes] of Object.entries(entries)) {const relative=path.replace('BOOT-INF/classes/','');migrationHashes[relative]=hash(bytes);assert.equal(await digest(join(root,'src/main/resources',relative)),hash(bytes));}
    report.legacyJarSha256=await digest(legacyJar);report.jarSha256=await digest(jar);report.oldMigrationBytes=migrationHashes;
    report.capacity=await capacityCheck({directory,minimumDiskBytes:2*1024**3});
    const source=await createDeployment(root,join(directory,'legacy-storage'));resources.push(source);await start(source,legacyJar);
    const setup=await fetch(base+'/api/auth/setup',{method:'POST',headers:{'Content-Type':'application/json','X-SupportOps-Request':'1'},body:JSON.stringify(account)});assert.equal(setup.status,200);
    const admin=await login(),identity=await admin('GET','/auth/me');database=await connection(source);const runId=randomUUID(),now=new Date().toISOString();
    await query(database,'INSERT INTO supportops.runs(id,session_id,question,status,answer,error_code,created_at,finished_at,input_tokens,output_tokens,user_id) VALUES(?,?,?,?,?,?,?,?,?,?,?)',
      [runId,randomUUID(),'旧版本合成诊断','COMPLETED','旧回答原文保持不变','',now,now,23,9,identity.id]);
    await query(database,'INSERT INTO supportops.run_events(run_id,kind,content,created_at) VALUES(?,?,?,?)',[runId,'answer',JSON.stringify({text:'旧事件保持不变'}),now]);
    await admin('POST','/memories',{content:'人员确认的旧约束',confirmed:true});const documents=await admin('POST','/documents/import-examples');
    for(const item of documents)await until(async()=>{const detail=await admin('GET','/documents/'+item.id);return detail.document.processingStatus==='READY';},120000,'LEGACY_DOCUMENT_TIMEOUT');
    const dataset=await admin('POST','/evaluations/datasets/import-builtin');
    // 实际旧程序写入结果、案例、Excel和普通用户归档；仅使用合成数据。
    const oldOutcome=await admin('POST','/runs/'+runId+'/outcome',outcome());
    const createdCase=await admin('POST','/evaluations/case-library',{requestId:randomUUID(),sourceRunId:runId,expectedOutcomeRevisionId:oldOutcome.revision.id},201);
    let oldCase=await admin('GET','/evaluations/case-library/'+createdCase.resourceId);
    await admin('POST','/evaluations/case-library/'+oldCase.id+'/draft',{requestId:randomUUID(),expectedVersion:oldCase.version,basedOnRevisionId:null,draft:draft('migration','旧版本合成案例')});
    oldCase=await admin('GET','/evaluations/case-library/'+oldCase.id);
    const member=await admin('POST','/users',{username:'legacy_member',password:'Synthetic-LegacyMember88',role:'USER'});
    const archivedRun=randomUUID();await query(database,'INSERT INTO supportops.runs(id,session_id,question,status,answer,error_code,created_at,user_id,archived_at) VALUES(?,?,?,?,?,?,?,?,?)',[archivedRun,randomUUID(),'旧普通用户归档','COMPLETED','旧本人回答','',now,member.id,now]);
    const unknownRun=randomUUID();await query(database,'INSERT INTO supportops.runs(id,session_id,question,status,answer,error_code,created_at,user_id) VALUES(?,?,?,?,?,?,?,NULL)',[unknownRun,randomUUID(),'无归属未报告Token历史','COMPLETED','保留历史','',now]);
    const xml={
      '[Content_Types].xml':'<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>',
      '_rels/.rels':'<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>',
      'xl/workbook.xml':'<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="合成金额" sheetId="1" r:id="rId1"/></sheets></workbook>',
      'xl/_rels/workbook.xml.rels':'<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>',
      'xl/worksheets/sheet1.xml':'<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>金额</t></is></c></row><row r="2"><c r="A2"><v>12.5</v></c></row></sheetData></worksheet>'};
    const excelBytes=zipSync(Object.fromEntries(Object.entries(xml).map(([path,value])=>[path,strToU8(value)])));
    const form=new FormData();form.set('file',new Blob([excelBytes]),'legacy.xlsx');form.set('title','旧合成表格');form.set('version','2.0');form.set('requestKey',randomUUID());
    const uploadResponse=await fetch(base+'/api/documents/uploads',{method:'POST',headers:{Cookie:setup.headers.get('set-cookie').split(';')[0],'X-SupportOps-Request':'1'},body:form});assert.equal(uploadResponse.status,202);
    const upload=await uploadResponse.json();const excelUpload=await until(async()=>{const value=await admin('GET','/documents/uploads/'+upload.id);assert.notEqual(value.status,'FAILED');return value.status==='SUCCEEDED'?value:false;},120000,'LEGACY_EXCEL_TIMEOUT');
    const tables=await admin('GET','/documents/batches/'+excelUpload.batchId+'/datasets');assert.equal(tables.length,1);
    const oldExcel=await admin('POST','/documents/datasets/'+tables[0].id+'/query',{sql:'SELECT COUNT(*) AS count FROM data',version:'2.0'});assert.deepEqual(oldExcel.rows,[['1']]);
    const oldDocuments=await Promise.all(documents.map(item=>admin('GET','/documents/'+item.id)));
    const oldChunks=await query(database,'SELECT * FROM supportops.chunks ORDER BY id');
    const oldObjects=await query(database,'SELECT * FROM supportops.source_files ORDER BY id');
    await query(database,'INSERT INTO supportops.feishu_bindings(id,app_id,tenant_key,user_id,open_id,created_at) VALUES(?,?,?,?,?,?)',[randomUUID(),'synthetic-legacy-app','synthetic-tenant',identity.id,'synthetic-open-id',now]);
    await admin('POST','/demo/scenario',{scenario:'migration'});
    const plan=await admin('POST','/remediations',{requestId:randomUUID(),actionId:'ORDERBRIDGE_MIGRATE_SYNC_PATH',name:'旧批准等待执行',reason:'独立恢复演练',sourceRunId:runId});
    const approved=await admin('POST','/remediations/'+plan.id+'/approve',{requestId:randomUUID(),expectedVersion:plan.version,planHash:plan.planHash,reason:'人工批准'});
    const unknown=await admin('POST','/remediations',{requestId:randomUUID(),actionId:'ORDERBRIDGE_MIGRATE_SYNC_PATH',name:'旧UNKNOWN故障注入',reason:'记录不明状态，不执行',sourceRunId:runId});
    const executionId=randomUUID();await query(database,'INSERT INTO supportops.remediation_executions(id,plan_id,target_id,operation_id,verification_request_id,instance_id,process_id,process_started_at,actor_id,status,application_status,verification_status,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)',
      [executionId,unknown.id,'BUILTIN_ORDERBRIDGE',randomUUID(),randomUUID(),unknown.plan.before.instanceId,runtime.record.pid,runtime.record.processStartedAt,identity.id,'UNKNOWN','UNKNOWN','UNKNOWN',now]);
    const beforeMigrations=await query(database,'SELECT version,checksum,success FROM supportops.flyway_schema_history ORDER BY installed_rank');assert.equal(beforeMigrations.at(-1).version,'11');
    const beforeRun=await query(database,'SELECT * FROM supportops.runs WHERE id=?',[runId]),beforeEvents=await query(database,'SELECT * FROM supportops.run_events WHERE run_id=?',[runId]);
    await closeDatabase(database);database=null;await runtime.stop();const descriptor=JSON.parse(await readFile(join(source.directory,'deployment.json'),'utf8'));
    const before=await databaseSnapshot(descriptor),planPath=join(directory,'backup-plan.json');await backupPlan(descriptor,planPath);const backup=await makeBackup(planPath,source.deploymentId,join(directory,'old-backup'));
    const suspension=await suspendAfterBackup(descriptor,backup);report.sourceStorageSuspension=suspension.evidence;
    try{
    await capacityCheck({directory,minimumDiskBytes:2*1024**3});
    const {deployment:restored}=await restoreBackup(root,join(directory,'old-backup'),join(directory,'upgraded-storage'),{targetId:randomUUID()});resources.push(restored);assert.deepEqual(await databaseSnapshot(restored),before);
    await start(restored,jar);database=await connection(restored);const upgraded=await login();
    assert.equal((await upgraded('GET','/auth/me')).id,identity.id);
    const memories=await upgraded('GET','/memories');assert.equal(memories.length,1);assert.equal(memories[0].content,'人员确认的旧约束');
    for(const item of documents){const detail=await upgraded('GET','/documents/'+item.id);assert.equal(detail.document.processingStatus,'READY');}
    const lexical=await upgraded('GET','/documents/search?query=sync.targetPath&version=2.0&lexicalOnly=true');
    assert.equal(lexical.mode,'LEXICAL');assert.ok(lexical.passages.length>0);assert.ok(lexical.passages.every(item=>['2.0','*'].includes(item.version)));
    assert.equal((await query(database,'SELECT COUNT(*) n FROM supportops.feishu_bindings WHERE user_id=?',[identity.id]))[0].n,'1');
    const afterRun=await query(database,'SELECT * FROM supportops.runs WHERE id=?',[runId]);assert.deepEqual(afterRun.map(row=>Object.fromEntries(Object.keys(beforeRun[0]).map(key=>[key,row[key]]))),beforeRun);assert.deepEqual(await query(database,'SELECT * FROM supportops.run_events WHERE run_id=?',[runId]),beforeEvents);
    assert.deepEqual(await query(database,"SELECT version,checksum,success FROM supportops.flyway_schema_history WHERE version NOT IN ('12','13','14','15','16','17','18') ORDER BY installed_rank"),beforeMigrations);
    await upgraded('GET','/evaluations/datasets/'+dataset.id,undefined,410);
    assert.equal((await query(database,'SELECT content_sha256 FROM supportops.evaluation_datasets WHERE id=?',[dataset.id]))[0].content_sha256,dataset.contentSha256);
    assert.equal((await upgraded('GET','/runs/'+runId+'/outcome')).version,1);
    await upgraded('GET','/evaluations/case-library/'+oldCase.id,undefined,410);
    assert.equal((await query(database,'SELECT draft_hash FROM supportops.evaluation_case_entries WHERE id=?',[oldCase.id]))[0].draft_hash,oldCase.draftHash);
    assert.deepEqual((await upgraded('POST','/documents/datasets/'+tables[0].id+'/query',{sql:'SELECT COUNT(*) AS count FROM data',version:'2.0'})).rows,oldExcel.rows);
    assert.deepEqual(await query(database,'SELECT * FROM supportops.chunks ORDER BY id'),oldChunks);
    assert.deepEqual(await query(database,'SELECT * FROM supportops.source_files ORDER BY id'),oldObjects);
    assert.equal((await query(database,"SELECT COUNT(*) n FROM supportops.run_target_snapshots WHERE snapshot_kind='LEGACY_INFERRED'"))[0].n,'3');
    assert.equal((await query(database,'SELECT COUNT(*) n FROM supportops.runs WHERE id=? AND archived_at=?',[archivedRun,now]))[0].n,'1');
    assert.equal((await query(database,"SELECT COUNT(*) n FROM supportops.runs WHERE id=? AND actor_type='LEGACY_UNKNOWN'",[unknownRun]))[0].n,'1');
    const saved=await upgraded('POST','/runs/'+runId+'/outcome',{requestId:randomUUID(),expectedVersion:1,status:'UNCONFIRMED',actionsTaken:'旧库升级后待人工观察',verificationSummary:'尚未验证',remainingIssue:'',changeReason:'增量升级后人工复核',evidence:[]});assert.equal(saved.revision.version,2);
    assert.equal((await upgraded('GET','/remediations/'+plan.id)).status,'STALE');
    await upgraded('POST','/remediations/'+plan.id+'/execute',{requestId:randomUUID(),expectedVersion:approved.version,planHash:plan.planHash},409);
    assert.equal((await upgraded('GET','/remediations/'+unknown.id)).execution.status,'UNKNOWN');
    assert.equal((await query(database,'SELECT COUNT(*) n FROM supportops.remediation_executions'))[0].n,'1');
    assert.equal((await query(database,'SELECT COUNT(*) n FROM supportops.demo_environment_events WHERE kind=?',['REMEDIATION_VERIFICATION']))[0].n,'0');
    await atomicJson(join(directory,'private/upgrade-snapshot.json'),{before,after:await databaseSnapshot(restored),beforeMigrations,beforeRun,beforeEvents,runId,dataset,approved,unknown});
    report.checks=[{id:'EIT-01',status:'PASS',proof:{oldMigrationsUnchanged:9,realV11Upgrade:true,oldRunEventsTokensPreserved:true,newOutcomeWritable:true,oldModulesRead:['users','runs','archives','legacy-unknown','knowledge','excel','original-files','chunks','memory','feishu','evaluations','outcomes','case-library','remediation'],legacyLexicalPassages:lexical.passages.length}},
      {id:'EIT-31',status:'PASS',proof:{backupId:backup.backupId,restoredExactBeforeStartup:true,oldApprovalStale:true,unknownNotReplayed:true,realStorage:true}}];report.status='PASS';
    }finally{if(database){await closeDatabase(database);database=null;}await runtime.stop();await suspension.resume();}
  } catch(error) {report.status='FAIL';await writeFile(join(directory,'private/failure.txt'),error.stack || String(error));console.error('UPGRADE_FAILED: private evidence retained');}
  finally {
    if(database){try{await closeDatabase(database);}catch(error){report.databaseCloseStatus='FAILED';report.status='FAIL';await writeFile(join(directory,'private/database-close-failure.txt'),error.stack || String(error));}}
    if(runtime){try{await runtime.stop();report.applicationStopped=true;}catch{report.applicationStopped=false;}}
    for(const deployment of resources) {let state='RETAINED';if(!input.keep&&report.applicationStopped){try{await cleanDeployment(JSON.parse(await readFile(join(deployment.directory,'deployment.json'),'utf8')));state='CLEANED';}catch{state='CLEANUP_FAILED_RETAINED';report.status='FAIL';}}
      report.resources.push({deploymentId:deployment.deploymentId,directory:deployment.directory,project:deployment.project,state});}
    report.finishedAt=new Date().toISOString();await atomicJson(join(directory,'public/report.json'),report);console.log('Upgrade '+report.status+': '+join(directory,'public/report.json'));
  }
  return report;
}
if(process.argv[1]&&import.meta.url===pathToFileURL(resolve(process.argv[1])).href){try{const result=await upgradeChecks(options(process.argv.slice(2),['directory','jar','legacy-jar'],['keep']));process.exitCode=result.status==='PASS'?0:1;}catch{console.error('UPGRADE_INPUT_OR_ENVIRONMENT_FAILED');process.exitCode=1;}}
