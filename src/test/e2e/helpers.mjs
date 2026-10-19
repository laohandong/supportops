import {readFile, writeFile} from 'node:fs/promises';
import {join} from 'node:path';
import {expect} from '@playwright/test';
import mysql from 'mysql2/promise';

export async function suite() { return JSON.parse(await readFile(process.env.RD_SUITE, 'utf8')); }
export async function login(page, user, {setup = false} = {}) {
  await page.goto('/login.html');
  await expect(page.locator('#login-submit')).toBeEnabled();
  if (setup) await expect(page.locator('#login-title')).toContainText('管理员');
  await page.locator('#username').fill(user.username); await page.locator('#password').fill(user.password);
  await page.locator('#login-submit').click();
  await expect(page.locator('#current-user')).toContainText(user.username);
  await expect(page.locator('body')).not.toHaveClass(/auth-pending/);
}
/** 等待真实退出后的页面跳转完成，避免登录导航与仍在途的退出跳转互相中断。 */
export async function logout(page) {
  await Promise.all([page.waitForURL('**/login.html'), page.locator('#logout').click()]);
  await expect(page.locator('#login-submit')).toBeEnabled();
}
export async function call(page, method, path, body, expected = 200) {
  const response = await page.request.fetch('/api' + path, {method, headers: {'X-SupportOps-Request': '1'}, ...(body === undefined ? {} : {data: body})});
  expect(response.status(), `${method} ${path}`).toBe(expected);
  const content = await response.text();
  return content ? JSON.parse(content) : null;
}
export async function rows(sql, params = []) {
  const data = await suite(), database = await mysql.createConnection({host: '127.0.0.1', port: data.deployment.ports.mysql,
    user: 'root', password: 'synthetic-knowledge-root', connectTimeout: 4000, supportBigNumbers: true, bigNumberStrings: true, dateStrings: true});
  try { return (await database.query({sql, timeout: 8000}, params))[0]; } finally { await database.end(); }
}
export async function evidence(id, value) {
  const data = await suite(); await writeFile(join(data.directory, 'evidence-' + id + '.json'), JSON.stringify({schemaVersion: 1, caseId: id, ...value}, null, 2));
}
/** 使用实际菜单按钮打开窄屏抽屉；不强制点击隐藏入口或绕过inert。 */
export async function openNavigation(page) {
  await expect(page.locator('body')).not.toHaveClass(/auth-pending/);
  const toggle=page.locator('#navigation-toggle');
  if(await toggle.isVisible() && await toggle.getAttribute('aria-expanded')!=='true') {
    await toggle.click();
    await expect(toggle).toHaveAttribute('aria-expanded','true');
    await expect(page.locator('#workspace-navigation')).toBeVisible();
  }
}
/** 通过真实可见的导航入口切换页面，同时覆盖窄屏抽屉关闭与页面焦点恢复。 */
export async function nav(page, name) {
  await openNavigation(page);
  await page.locator(`.nav-item[data-page="${name}"]`).click();
  await expect(page.locator(`#page-${name}`)).toBeVisible();
}
export async function scenario(page, value = 'migration') { await call(page, 'POST', '/demo/scenario', {scenario: value}); }
export async function createProposal(page, name) {
  await nav(page, 'remediation'); await page.getByRole('button', {name: '新建修复提案', exact: true}).click();
  await page.locator('#remediation-name').fill(name); await page.locator('#remediation-reason').fill('本轮合成浏览器回归');
  await page.getByRole('button', {name: '创建待审批提案', exact: true}).click();
  await expect(page.locator('#remediation-content h2').first()).toHaveText(name);
  const matching = await rows('SELECT id FROM supportops.remediation_plans WHERE name=?', [name]);
  expect(matching).toHaveLength(1); return matching[0].id;
}
export async function approve(page) {
  await page.locator('#remediation-decision-reason').fill('已核对合成现场');
  await page.getByRole('button', {name: '批准修复', exact: true}).click();
  await expect(page.getByRole('button', {name: '执行已批准修复', exact: true})).toBeEnabled();
}
export async function closePending(page) { await page.getByRole('button', {name: '查询原请求', exact: true}).click(); await expect(page.getByRole('heading', {name: '原请求待确认'})).toHaveCount(0); }
export async function pending(page, userId) { return page.evaluate(id => JSON.parse(localStorage.getItem('supportops.remediation.pending.v1.' + id) || 'null'), userId); }

/** route.fetch 完成真实后端请求，独立数据库证明受理后才丢弃浏览器响应。 */
export async function loseAcceptedResponse(page, pattern, verify) {
  let resolveCaptured, rejectCaptured;
  const captured = new Promise((done, reject) => { resolveCaptured = done; rejectCaptured = reject; });
  await page.route(pattern, async route => {
    if (route.request().method() !== 'POST') { await route.continue(); return; }
    try {
      const body = route.request().postDataJSON();
      const response = await route.fetch({maxRetries: 0});
      const receipt = await response.json();
      await verify(body, receipt, response.status());
      resolveCaptured({method: route.request().method(), path: new URL(route.request().url()).pathname, body, status: response.status(), receipt});
      await route.abort('connectionclosed');
    } catch (error) { rejectCaptured(error); await route.abort(); }
  }, {times: 1});
  return {captured};
}
