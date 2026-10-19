import assert from 'node:assert/strict';
import {zipSync, strToU8} from 'fflate';
import {randomUUID} from 'node:crypto';
import {hash, until} from './common.mjs';

/** HTTP 登录信息只驻留内存；结果证据不记录 Cookie、密码或密钥。 */
export async function authenticatedApi(base, account) {
  const login = await fetch(base + '/api/auth/login', {method: 'POST', headers: {'Content-Type': 'application/json', 'X-SupportOps-Request': '1'}, body: JSON.stringify(account)});
  assert.equal(login.status, 200); const cookie = login.headers.get('set-cookie')?.split(';')[0]; assert.ok(cookie);
  return async (method, path, body, expected = 200) => {
    const form = body instanceof FormData;
    const response = await fetch(base + '/api' + path, {method, headers: {'X-SupportOps-Request': '1', Cookie: cookie, ...(!form && body !== undefined ? {'Content-Type': 'application/json'} : {})},
      ...(body === undefined ? {} : {body: form ? body : JSON.stringify(body)}), signal: AbortSignal.timeout(60000)});
    assert.equal(response.status, expected, method + ' ' + path);
    if (path.endsWith('/original')) return Buffer.from(await response.arrayBuffer());
    const text = await response.text(); return text ? JSON.parse(text) : null;
  };
}

/** 最小有效 XLSX：两条合成记录，保留原行号和数值，实际由 EasyExcel 导入。 */
export function syntheticExcel(seed = randomUUID()) {
  const xml = files => Object.fromEntries(Object.entries(files).map(([name,value]) => [name,strToU8(value)]));
  return zipSync(xml({
    '[Content_Types].xml': '<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>',
    '_rels/.rels': '<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>',
    'xl/workbook.xml': '<?xml version="1.0"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="恢复明细" sheetId="1" r:id="rId1"/></sheets></workbook>',
    'xl/_rels/workbook.xml.rels': '<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>',
    'xl/worksheets/sheet1.xml': '<?xml version="1.0"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>订单编号</t></is></c><c r="B1" t="inlineStr"><is><t>数量</t></is></c></row><row r="2"><c r="A2" t="inlineStr"><is><t>RD-A</t></is></c><c r="B2"><v>7</v></c></row><row r="3"><c r="A3" t="inlineStr"><is><t>RD-B</t></is></c><c r="B3"><v>11</v></c></row></sheetData><!-- '+seed+' --></worksheet>'
  }), {level: 6});
}

async function upload(api, filename, bytes, version, documentId) {
  const form = new FormData(); form.set('file', new Blob([bytes]), filename); form.set('title','RD 恢复合成资料'); form.set('version',version); form.set('requestKey',randomUUID());
  if (documentId) form.set('documentId',documentId);
  const result = await api('POST','/documents/uploads',form,202);
  await until(async () => { const state = await api('GET','/documents/uploads/'+result.id); if (state.status === 'FAILED') throw new Error('SYNTHETIC_UPLOAD_FAILED'); return state.status === 'SUCCEEDED'; },120000,'SYNTHETIC_UPLOAD_TIMEOUT');
  return {...result, originalSha256: hash(bytes)};
}

/** 丰富数据都经真实后台受理；UNKNOWN 的单点故障状态另外在演练账本中明确标记。 */
export async function seedRecovery(api, fixtureBase) {
  const seed = randomUUID();
  let settings = await api('GET','/model-settings');
  for (const role of ['model','embedding']) settings = await api('POST','/model-settings/'+role,{provider:'custom', model:'rd-recovery-fixture', baseUrl:fixtureBase+'/v1', apiKey:'synthetic-rd-recovery-fixture', keyAction:'replace', revision:settings.revision});
  const memory = await api('POST','/memories',{content:'RD 合成项目约束：所有恢复调用只允许本地协议夹具。',confirmed:true});
  const old = await upload(api,'rd-old.md',Buffer.from('# 旧版说明\nOLD_VERSION_SENTINEL orders.path /v1/orders 仅适用 1.0。\n'+seed),'1.0');
  const current = await upload(api,'rd-current.md',Buffer.from('# 当前说明\nsync.targetPath /v2/orders CURRENT_VERSION_SENTINEL 适用于 2.0。旧 orders.path 不能控制 2.0 默认路径。\n'+seed),'2.0',old.documentId);
  const excel = await upload(api,'rd-orders.xlsx',syntheticExcel(seed),'2.0');
  await api('POST','/documents/'+current.documentId+'/index');
  await until(async () => { const batches = await api('GET','/documents/'+current.documentId+'/batches'); return batches.some(batch => batch.id === current.batchId && batch.status === 'READY' && batch.vectorStatus === 'SUCCEEDED'); },120000,'SYNTHETIC_VECTOR_TIMEOUT');
  const datasets = await api('GET','/documents/datasets?version=2.0'); const dataset = datasets.find(item => item.documentId === excel.documentId); assert.ok(dataset); assert.equal(dataset.rowCount,2);
  const excelQuery = await api('POST','/documents/datasets/'+dataset.id+'/query',{version:'2.0',sql:'SELECT source_row,c_0,c_1 FROM data ORDER BY source_row'}); assert.equal(excelQuery.rows.length,2);
  assert.ok(JSON.stringify(excelQuery.rows).includes('RD-A')); assert.ok(JSON.stringify(excelQuery.rows).includes('RD-B'));
  const lexical = await api('GET','/documents/search?query=sync.targetPath&version=2.0&lexicalOnly=true');
  const vector = await api('GET','/documents/search?query=sync.targetPath&version=2.0&lexicalOnly=false');
  assert.equal(lexical.mode,'LEXICAL'); assert.equal(vector.mode,'HYBRID');
  for (const result of [lexical,vector]) { assert.ok(JSON.stringify(result).includes('CURRENT_VERSION_SENTINEL')); assert.ok(!JSON.stringify(result).includes('OLD_VERSION_SENTINEL')); }
  const archived = await api('POST','/runs',{question:'RD 恢复归档历史 '+seed,lexicalOnly:true});
  await until(async()=>{const run=await api('GET','/runs/'+archived.id);if(['FAILED','CANCELLED','TIMED_OUT'].includes(run.status))throw new Error('SYNTHETIC_DIAGNOSIS_FAILED');return run.status==='COMPLETED';},60000,'SYNTHETIC_DIAGNOSIS_TIMEOUT');
  await api('POST','/runs/'+archived.id+'/archive');
  const outcome = await api('POST','/runs/'+archived.id+'/outcome',{requestId:randomUUID(),expectedVersion:0,status:'UNCONFIRMED',actionsTaken:'合成恢复验证',verificationSummary:'仅验证持久化',remainingIssue:'未开展真实效果评审',changeReason:'',evidence:[]});
  const detail = await api('GET','/runs/'+archived.id+'/outcome');
  const exported = await api('POST','/runs/'+archived.id+'/evaluation-exports',{requestId:randomUUID(),expectedOutcomeRevisionId:detail.outcome.id,redactedQuestion:'合成恢复来源',redactedSummary:'未确认真实处置效果',redactionConfirmed:true},201);
  const sourcePackage = await api('GET','/evaluation-exports/'+exported.exportId);
  await api('POST','/demo/scenario',{scenario:'migration'});
  const proposal = async name => api('POST','/remediations',{requestId:randomUUID(),actionId:'ORDERBRIDGE_MIGRATE_SYNC_PATH',name,reason:'本轮恢复合成故障现场'});
  const approval = async plan => api('POST','/remediations/'+plan.id+'/approve',{requestId:randomUUID(),expectedVersion:plan.version,planHash:plan.planHash,reason:'已核对合成现场'});
  const applied=await approval(await proposal('RD 历史执行合成故障状态'));
  const execution = await api('POST','/remediations/'+applied.id+'/execute',{requestId:randomUUID(),expectedVersion:applied.version,planHash:applied.planHash}); assert.equal(execution.execution.status,'SUCCEEDED');
  await api('POST','/demo/scenario',{scenario:'migration'});
  const oldApproval=await approval(await proposal('RD 恢复前旧审批'));
  return {memory,old,current,excel,dataset,excelQuery,lexical,vector,archivedRunId:archived.id,exportId:exported.exportId,sourcePackage,unknownExecutionId:execution.execution.id,oldApproval};
}
