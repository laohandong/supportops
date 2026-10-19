import {test, expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
import {join} from 'node:path';
import {suite, login, logout, call, rows, evidence, nav, scenario, createProposal, approve, closePending, pending, loseAcceptedResponse} from './helpers.mjs';

test.beforeEach(async ({page}, info) => {
  if (!info.title.startsWith('E01')) await login(page, (await suite()).accounts.admin);
});
test.afterEach(async ({page}, info) => {
  if (info.status !== 'passed' || page.isClosed()) return;
  const data = await suite(), id = /E\d{2}/.exec(info.title)[0];
  // 分享截图只来自合成账号；密码和密钥控件一律遮盖，trace/Cookie 留在私有目录外不生成。
  await page.screenshot({path: join(data.directory, 'public', id + '.png'), fullPage: true,
    mask: [page.locator('input[type=password]')], animations: 'disabled'});
});

async function adminId() { return (await rows('SELECT id FROM supportops.app_users WHERE username=?', [(await suite()).accounts.admin.username]))[0].id; }
async function durableExecution(planId) {
  await expect.poll(async () => (await rows('SELECT status FROM supportops.remediation_executions WHERE plan_id=?', [planId]))[0]?.status).toBe('SUCCEEDED');
  const execution = (await rows('SELECT * FROM supportops.remediation_executions WHERE plan_id=?', [planId]))[0];
  const receipt = JSON.parse(execution.receipt_json);
  expect(execution.application_status).toBe('APPLIED'); expect(execution.verification_status).toBe('PASSED');
  expect(receipt.verification.httpStatus).toBe(200); expect(receipt.verification.accepted).toBe(true);
  return {execution, receipt};
}

test('E01 首次初始化、退出与普通用户边界；D06 运行 API 权限', async ({page, browser}) => {
  const data = await suite();
  expect((await page.request.get('/api/ops/runtime')).status()).toBe(401);
  await login(page, data.accounts.admin, {setup: true});
  const runtime = await call(page, 'GET', '/ops/runtime');
  expect(runtime.phase).toBe('READY'); expect(runtime.jarSha256).toBe(data.deployment.jarSha256);
  expect(runtime.runtimeId).toBe(data.deployment.processes[0].runtimeId); expect(runtime.pid).toBeGreaterThan(0);
  expect(JSON.stringify(runtime)).not.toMatch(/password|apiKey|cookie|synthetic-rd-fixture/i);
  await nav(page, 'users');
  await expect(page.locator('#runtime-information')).toBeVisible();
  for (const [user, role] of [[data.accounts.otherAdmin, 'ADMIN'], [data.accounts.user, 'USER']]) {
    await page.locator('#new-username').fill(user.username); await page.locator('#new-password').fill(user.password);
    await page.locator('#new-role').selectOption(role); await page.locator('#create-user-submit').click();
    await expect(page.locator('#user-list')).toContainText(user.username);
  }
  await nav(page, 'diagnosis'); await page.locator('#question').fill('RD-E01 管理员诊断'); await page.locator('#lexical-only').check();
  await page.locator('#start-run').click(); await expect(page.locator('#run-state')).toHaveText('已完成', {timeout: 60000});
  const run = (await rows('SELECT id,user_id FROM supportops.runs WHERE question=?', ['RD-E01 管理员诊断']))[0];
  await logout(page); await expect(page).toHaveURL(/login\.html/);
  expect((await page.request.get('/api/ops/runtime')).status()).toBe(401);
  await login(page, data.accounts.user);
  await expect(page.locator('.admin-navigation')).toBeHidden(); await expect(page.locator('#runtime-information')).toBeHidden();
  for (const path of ['/api/ops/runtime', '/api/model-settings', '/api/evaluations/experiments', '/api/remediations']) expect((await page.request.get(path)).status(), path).toBe(403);
  expect((await page.request.get('/api/runs/' + run.id)).status()).toBe(404);
  await expect(page.locator('#history')).not.toContainText('RD-E01');
  await logout(page); await login(page, data.accounts.admin);
  await evidence('E01', {status: 'PASS', runtime, accounts: ['ADMIN', 'ADMIN', 'USER'], foreignRun: run.id, permissionStatuses: {anonymous: 401, user: 403, admin: 200}});
});

test('E02 SSE 断线、游标与刷新读取原诊断', async ({page}) => {
  const data = await suite(), accepted = [], streams = [];
  page.on('request', request => { const url = new URL(request.url()); if (request.method() === 'POST' && url.pathname === '/api/runs') accepted.push(request.postDataJSON()); if (url.pathname.endsWith('/stream')) streams.push(url.searchParams.get('after')); });
  await page.request.post(data.fixtureBase + '/control', {data: {delayMs: 400}});
  await page.request.post('/__rd/control', {headers: {'x-rd-control': data.controlToken}, data: {dropStreams: 1}});
  const before = await rows('SELECT id FROM supportops.runs');
  await page.locator('#question').fill('RD-E02 SSE 断线与刷新'); await page.locator('#lexical-only').check(); await page.locator('#start-run').click();
  await expect.poll(async () => (await rows('SELECT id FROM supportops.runs WHERE question=?', ['RD-E02 SSE 断线与刷新'])).length).toBe(1);
  const runId = (await rows('SELECT id FROM supportops.runs WHERE question=?', ['RD-E02 SSE 断线与刷新']))[0].id;
  await expect.poll(() => streams.length).toBeGreaterThanOrEqual(2);
  expect(streams.some(cursor => Number(cursor) > 0)).toBe(true);
  await page.reload(); await page.locator('#history .history-item').filter({hasText: 'RD-E02 SSE 断线与刷新'}).click();
  await expect(page.locator('#run-state')).toHaveText('已完成', {timeout: 60000});
  await page.request.post(data.fixtureBase + '/control', {data: {delayMs: 0}});
  const final = await call(page, 'GET', '/runs/' + runId), events = await call(page, 'GET', '/runs/' + runId + '/events?after=0');
  const requestsBefore = (await (await page.request.get(data.fixtureBase + '/requests')).json()).length;
  const stable = JSON.stringify(await rows('SELECT input_tokens,output_tokens,status,answer FROM supportops.runs WHERE id=?', [runId]));
  await page.reload(); await page.locator('#history .history-item').filter({hasText: 'RD-E02 SSE 断线与刷新'}).click();
  await expect(page.locator('#run-state')).toHaveText('已完成');
  expect(JSON.stringify(await rows('SELECT input_tokens,output_tokens,status,answer FROM supportops.runs WHERE id=?', [runId]))).toBe(stable);
  expect((await (await page.request.get(data.fixtureBase + '/requests')).json()).length).toBe(requestsBefore);
  expect(accepted).toHaveLength(1); expect((await rows('SELECT id FROM supportops.runs')).length).toBe(before.length + 1);
  expect(new Set(events.map(event => event.id)).size).toBe(events.length);
  const usages = events.filter(event => event.kind === 'USAGE').map(event => event.content);
  expect(usages.length).toBeGreaterThan(0);
  for (const usage of usages) { expect(typeof usage.inputTokens).toBe('number'); expect(typeof usage.outputTokens).toBe('number'); }
  expect(final.inputTokens).toBe(usages.reduce((total, value) => total + value.inputTokens, 0));
  expect(final.outputTokens).toBe(usages.reduce((total, value) => total + value.outputTokens, 0));
  expect(await page.evaluate(() => window.ecUnsafeExecuted)).toBeUndefined();
  await evidence('E02', {status: 'PASS', runId, streams, acceptanceCount: accepted.length, events: events.map(event => ({id: event.id, kind: event.kind})), usage: {input: final.inputTokens, output: final.outputTokens}, fixtureRequestCount: requestsBefore});
});

test('E03 旧评测读写入口明确返回410且不跳转', async ({page}) => {
  const before=await rows('SELECT COUNT(*) n FROM supportops.evaluation_experiments');
  for(const method of ['GET','POST']) {
    const response=await page.request.fetch('/api/evaluations/experiments',{method,headers:{'X-SupportOps-Request':'1'},...(method==='POST'?{data:{name:'retired'}}:{})});
    expect(response.status()).toBe(410);expect(response.headers().location).toBeUndefined();expect(await response.text()).toContain('EVALUATION_MOVED');
  }
  expect(await rows('SELECT COUNT(*) n FROM supportops.evaluation_experiments')).toEqual(before);
  await evidence('E03',{status:'PASS',reads:410,writes:410,forwarded:false});
});

test('E04 诊断来源导出固定请求可查询且正文变化被拒绝', async ({page}) => {
  const run=await call(page,'POST','/runs',{question:'合成导出回归'});
  await expect.poll(async()=> (await call(page,'GET','/runs/'+run.id)).status,{timeout:60000}).toBe('COMPLETED');
  await call(page,'POST','/runs/'+run.id+'/outcome',{requestId:randomUUID(),expectedVersion:0,status:'UNCONFIRMED',actionsTaken:'合成验证',verificationSummary:'来源回执',remainingIssue:'未确认真实效果',changeReason:'',evidence:[]});
  const detail=await call(page,'GET','/runs/'+run.id+'/outcome');
  const body={requestId:randomUUID(),expectedOutcomeRevisionId:detail.outcome.id,redactedQuestion:'已脱敏问题',redactedSummary:'已脱敏摘要',redactionConfirmed:true};
  const receipt=await call(page,'POST','/runs/'+run.id+'/evaluation-exports',body,201);
  expect(await call(page,'GET','/evaluation-exports/requests/'+body.requestId)).toEqual(receipt);
  expect(await call(page,'POST','/runs/'+run.id+'/evaluation-exports',body,201)).toEqual(receipt);
  await call(page,'POST','/runs/'+run.id+'/evaluation-exports',{...body,redactedSummary:'不同正文'},409);
  const source=await call(page,'GET','/evaluation-exports/'+receipt.exportId);expect(source.content.redactedQuestion).toBe('已脱敏问题');
  await evidence('E04',{status:'PASS',runId:run.id,exportId:receipt.exportId,immutable:true});
});

test('E05 真实提案审批执行、结果、历史和两种视口', async ({page}) => {
  await scenario(page); const id = await createProposal(page, 'RD-E05 全流程'); await approve(page);
  await page.getByRole('button', {name: '执行已批准修复', exact: true}).click();
  await expect(page.locator('#remediation-content .rc-result')).toHaveText('验证通过'); const {execution, receipt} = await durableExecution(id);
  const demo = await call(page, 'GET', '/demo'); expect(demo.configuration.effective['sync.targetPath']).toBe('/v2/orders');
  expect(demo.lastRequest.requestId).toBe(execution.verification_request_id); expect(demo.lastRequest.httpStatus).toBe(200);
  await expect(page.getByRole('heading', {name: '配置应用', exact: true})).toBeVisible(); await expect(page.getByRole('heading', {name: '执行后验证', exact: true})).toBeVisible();
  const events = await call(page, 'GET', '/remediations/' + id + '/events'); expect(events.map(event => event.kind)).toEqual(expect.arrayContaining(['CREATED', 'APPROVE', 'ACCEPTED', 'FINISHED']));
  await page.reload(); await nav(page, 'remediation'); await page.getByRole('button', {name: 'RD-E05 全流程', exact: true}).click();
  for (const viewport of [{width: 1440, height: 960}, {width: 390, height: 844}]) {
    await page.setViewportSize(viewport); await expect(page.getByRole('heading', {name: '执行后验证', exact: true})).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).toBe(true);
    await page.screenshot({path: join((await suite()).directory, 'public', 'E05-' + viewport.width + '.png'), fullPage: true, animations: 'disabled'});
  }
  await evidence('E05', {status: 'PASS', id, execution, receipt, demo, auditKinds: events.map(event => event.kind), viewports: [1440, 390]});
});

test('E06 修复受理后丢响应、404、刷新与历史非法 pending', async ({page}) => {
  await scenario(page); const id = await createProposal(page, 'RD-E06 原执行'); await approve(page); const userId = await adminId();
  const recoverPattern = '**/api/remediations/requests/*';
  await page.route(recoverPattern, route => route.fulfill({status: 404, contentType: 'application/json', body: '{"code":"REMEDIATION_REQUEST_NOT_FOUND","message":"合成暂时读取失败"}'}));
  const {captured} = await loseAcceptedResponse(page, '**/api/remediations/' + id + '/execute', async (body, receipt, status) => {
    expect(status).toBe(200); expect(receipt.execution.id).toBeTruthy();
    expect(await rows('SELECT id FROM supportops.remediation_requests WHERE actor_id=? AND request_id=?', [userId, body.requestId])).toHaveLength(1);
    await durableExecution(id);
  });
  await page.getByRole('button', {name: '执行已批准修复', exact: true}).click(); const original = await captured;
  await expect(page.getByRole('heading', {name: '原请求待确认', exact: true})).toBeVisible();
  const frozen = await pending(page, userId); expect(frozen.body).toEqual(original.body); expect('/api' + frozen.path).toBe(original.path); expect(frozen.method).toBe('POST');
  await page.reload(); await nav(page, 'remediation'); expect(await pending(page, userId)).toEqual(frozen);
  await page.getByRole('button', {name: '查询原请求', exact: true}).click(); expect(await pending(page, userId)).toEqual(frozen);
  const afterExecution = await call(page, 'GET', '/demo');
  await page.unroute(recoverPattern); await closePending(page); expect(await pending(page, userId)).toBeNull();
  const retried = await call(page, 'POST', frozen.path, frozen.body); expect(retried.execution.id).toBe(original.receipt.execution.id);
  const afterRetry = await call(page, 'GET', '/demo'); expect(afterRetry.lastRequest).toEqual(afterExecution.lastRequest); expect(afterRetry.configuration.provided).toEqual(afterExecution.configuration.provided);
  expect(await rows('SELECT id FROM supportops.remediation_executions WHERE plan_id=?', [id])).toHaveLength(1);
  const invalidId = randomUUID(), illegal = {schemaVersion: 1, userId, method: 'POST', path: '/remediations', requestId: invalidId,
    body: {requestId: invalidId, actionId: 'ORDERBRIDGE_MIGRATE_SYNC_PATH', name: '历史非法意图', reason: '   '}};
  const plansBefore = (await rows('SELECT COUNT(*) AS total FROM supportops.remediation_plans'))[0].total;
  await page.evaluate(command => localStorage.setItem('supportops.remediation.pending.v1.' + command.userId, JSON.stringify(command)), illegal);
  await page.reload(); await nav(page, 'remediation'); await expect(page.getByRole('heading', {name: '原请求待确认', exact: true})).toBeVisible();
  expect(await pending(page, userId)).toEqual(illegal);
  await page.getByRole('button', {name: '原样重发原请求', exact: true}).click(); await expect.poll(() => pending(page, userId)).toBeNull();
  const rejection = await call(page, 'GET', '/remediations/requests/' + invalidId); expect(rejection.requestId).toBe(invalidId); expect(rejection.httpStatus).toBe(400); expect(rejection.errorCode).toBe('INVALID_INPUT');
  expect((await rows('SELECT COUNT(*) AS total FROM supportops.remediation_plans'))[0].total).toBe(plansBefore);
  await evidence('E06', {status: 'PASS', id, original, frozen, retryExecutionId: retried.execution.id, stableLastRequest: afterRetry.lastRequest, illegal, rejection});
});

test('E07 同一浏览器退出 A、进入 B、再进入 A 的固定意图隔离', async ({page}) => {
  const data = await suite(), userId = await adminId(), requestId = randomUUID();
  const original = {schemaVersion: 1, userId, method: 'POST', path: '/remediations', requestId,
    body: {requestId, actionId: 'ORDERBRIDGE_MIGRATE_SYNC_PATH', name: 'RD-E07 A 冻结意图', reason: '   '}};
  await page.evaluate(command => localStorage.setItem('supportops.remediation.pending.v1.' + command.userId, JSON.stringify(command)), original);
  await page.reload(); await nav(page, 'remediation'); await expect(page.getByRole('heading', {name: '原请求待确认', exact: true})).toBeVisible();
  const writes = []; page.on('request', request => { if (request.method() === 'POST' && new URL(request.url()).pathname.startsWith('/api/remediations')) writes.push({path: new URL(request.url()).pathname, body: request.postDataJSON()}); });
  await logout(page); await login(page, data.accounts.otherAdmin); await nav(page, 'remediation');
  await expect(page.getByRole('heading', {name: '原请求待确认', exact: true})).toHaveCount(0);
  expect(writes).toHaveLength(0); expect(await rows('SELECT id FROM supportops.remediation_requests WHERE request_id=?', [requestId])).toHaveLength(0);
  await logout(page); await login(page, data.accounts.admin); await nav(page, 'remediation');
  await expect(page.getByRole('heading', {name: '原请求待确认', exact: true})).toBeVisible(); expect(await pending(page, userId)).toEqual(original); expect(writes).toHaveLength(0);
  await page.getByRole('button', {name: '原样重发原请求', exact: true}).click(); await expect.poll(() => pending(page, userId)).toBeNull();
  expect(writes).toEqual([{path: '/api' + original.path, body: original.body}]);
  await evidence('E07', {status: 'PASS', original, originalWriteCount: writes.length, crossAccountWrites: 0});
});

test('E08 双页面模型配置冲突与同名场景重置拒绝旧审批', async ({page, context}) => {
  const second = await context.newPage(); await second.goto('/'); await nav(page, 'models'); await nav(second, 'models');
  await expect(page.locator('#model-name')).toBeVisible(); await expect(second.locator('#model-name')).toBeVisible();
  const firstRevision = (await call(page, 'GET', '/model-settings')).revision;
  await page.locator('#model-name').fill('rd-first-page'); await page.locator('#settings-model button[type=submit]').click();
  await expect(page.locator('#model-feedback')).toContainText('配置已保存');
  await second.locator('#model-name').fill('rd-stale-page'); await second.locator('#settings-model button[type=submit]').click();
  await expect(second.locator('#model-feedback')).toContainText('其他页面');
  const current = await call(page, 'GET', '/model-settings'); expect(current.revision).not.toBe(firstRevision); expect(current.model.model).toBe('rd-first-page');
  await scenario(page); const id = await createProposal(page, 'RD-E08 同名重置审批'); await approve(page);
  const approved = await call(page, 'GET', '/remediations/' + id), oldDemo = await call(page, 'GET', '/demo');
  await nav(page, 'demo'); await page.locator('#scenario').selectOption('migration');
  const reset = page.waitForResponse(response => new URL(response.url()).pathname === '/api/demo/scenario' && response.request().method() === 'POST');
  await page.locator('#set-scenario').click(); expect((await reset).status()).toBe(200);
  const stale = await call(page, 'GET', '/remediations/' + id); expect(stale.canExecute).toBe(false);
  const response = await call(page, 'POST', '/remediations/' + id + '/execute', {requestId: randomUUID(), expectedVersion: approved.version, planHash: approved.planHash}, 409);
  expect(await rows('SELECT id FROM supportops.remediation_executions WHERE plan_id=?', [id])).toHaveLength(0);
  const afterRejected = await call(page, 'GET', '/demo'); expect(afterRejected.lastRequest.targetPath).toBe('/v1/orders'); expect(afterRejected.configuration.effective['sync.targetPath']).toBe('/v1/orders');
  const events = await call(page, 'GET', '/demo/environment-events'); expect(events.filter(event => event.scenarioBefore === 'migration' && event.scenarioAfter === 'migration').length).toBeGreaterThan(0);
  await evidence('E08', {status: 'PASS', firstRevision, currentRevision: current.revision, acceptedModel: current.model.model, id, response, resetEvents: events, before: oldDemo, after: afterRejected}); await second.close();
});
