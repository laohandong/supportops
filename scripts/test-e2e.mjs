import {readFile, writeFile, mkdir, copyFile} from 'node:fs/promises';
import {createWriteStream} from 'node:fs';
import {resolve, join} from 'node:path';
import {pathToFileURL} from 'node:url';
import {randomUUID} from 'node:crypto';
import {options, atomicJson, privateDirectory, digest, execute, javaExecutable, until} from './reliability/common.mjs';
import {createDeployment, registerRuntime, cleanDeployment} from './reliability/deployment.mjs';
import {launch} from './reliability/runtime.mjs';
import {connection, closeDatabase, query} from './reliability/storage.mjs';
import {loadLocalConfig} from './local-config.mjs';
import {systemEnvironment} from './lib/runtime-safety.mjs';
import {startEvaluationProvider} from '../src/test/js/fixtures/evaluation-provider.mjs';
import {browserProxy} from './reliability/browser-proxy.mjs';

/** 从最终 JAR、独立 cwd 与白名单环境启动，主实例及评测子资源分别登记。 */
export async function runBrowser({directory, jar, keep = false, root = process.cwd()} = {}) {
  directory = resolve(directory || join(root, '.local/reliability-e2e', randomUUID()));
  jar = resolve(jar || join(root, 'target/supportops-0.1.0-SNAPSHOT.jar'));
  await privateDirectory(directory); await mkdir(join(directory, 'results')); await mkdir(join(directory, 'public'));
  const immutableJar = join(directory, 'application-under-test.jar'); await copyFile(jar, immutableJar); jar = immutableJar;
  let deployment, provider, runtime, proxy;
  const report = {schemaVersion: 1, suiteId: randomUUID(), platform: process.platform, node: process.version, jarSha256: await digest(jar),
    status: 'RUNNING', cases: [], cleanup: {application: 'NOT_CREATED', storage: 'NOT_CREATED', evaluation: 'NOT_CREATED'}};
  try {
    deployment = await createDeployment(root, join(directory, 'deployment'));
    provider = await startEvaluationProvider();
    const env = await loadLocalConfig(deployment.config, systemEnvironment());
    Object.assign(env, {SUPPORTOPS_MODEL_PROVIDER: 'custom', SUPPORTOPS_MODEL_BASE_URL: `http://127.0.0.1:${provider.port}/v1`,
      SUPPORTOPS_MODEL: 'rd-protocol-fixture', SUPPORTOPS_API_KEY: 'synthetic-rd-fixture',
      SUPPORTOPS_EMBEDDING_PROVIDER: 'disabled', SUPPORTOPS_FEISHU_ENABLED: 'false', SUPPORTOPS_TEST_MYSQL: `jdbc:mysql://127.0.0.1:${deployment.ports.mysql}/`,
      SUPPORTOPS_TEST_MINIO: `http://127.0.0.1:${deployment.ports.minio}`, SUPPORTOPS_TEST_ES: `http://127.0.0.1:${deployment.ports.elasticsearch}`});
    // 封存本轮实际配置；配置内容全部为专属资源或合成夹具，不能借用宿主凭据。
    await writeFile(deployment.config, Object.entries(env).filter(([key]) => key.startsWith('SUPPORTOPS_'))
      .map(([key, value]) => key + '=' + value).join('\n') + '\n', {mode: 0o600});
    const log = createWriteStream(join(directory, 'application.log'));
    runtime = await launch({root, env, jar, runtimeRoot: deployment.runtimeRoot, cwd: deployment.directory, port: 0,
      settings: deployment.settings, providers: '', deployment: join(deployment.directory, 'deployment.json'),
      applicationArgs: ['--spring.config.location=classpath:application.yml', '--supportops.knowledge.jobs.scan-ms=500'], log});
    await registerRuntime(deployment, runtime);
    const controlToken = randomUUID(); proxy = await browserProxy(runtime.base, controlToken);
    const password = 'Synthetic-RD-' + randomUUID();
    const data = {schemaVersion: 1, suiteId: report.suiteId, directory, deployment, base: proxy.base, backendBase: runtime.base,
      fixtureBase: `http://127.0.0.1:${provider.port}`, controlToken,
      accounts: {admin: {username: 'rd_admin_a', password}, otherAdmin: {username: 'rd_admin_b', password}, user: {username: 'rd_user', password}}};
    const suitePath = join(directory, 'suite-private.json'); await atomicJson(suitePath, data);
    const testEnv = {...systemEnvironment(), PLAYWRIGHT_BROWSERS_PATH: process.env.PLAYWRIGHT_BROWSERS_PATH || join(root, '.cache/reliability-browsers'),
      RD_SUITE: suitePath, RD_RESULTS: join(directory, 'results'), RD_BASE_URL: proxy.base};
    let testExit = 0;
    try { const result = await execute(process.execPath, [join(root, 'node_modules/@playwright/test/cli.js'), 'test', '-c', join(root, 'src/test/e2e/playwright.config.mjs')],
      {env: testEnv, cwd: root, windowsHide: true, timeout: 1200000, maxBuffer: 8 * 1024 * 1024}); await writeFile(join(directory, 'browser-console.log'), result.stdout + result.stderr); }
    catch (error) { testExit = 1; await writeFile(join(directory, 'browser-console.log'), String(error.stdout || '') + String(error.stderr || '')); }
    const result = JSON.parse(await readFile(join(directory, 'results/playwright-private.json'), 'utf8'));
    const collect = suites => suites.flatMap(value => [...value.specs.map(spec => ({id: /E\d{2}/.exec(spec.title)?.[0], title: spec.title,
      status: spec.ok ? 'PASS' : 'FAIL', attempts: spec.tests.flatMap(t => t.results).map(r => ({status: r.status, durationMs: r.duration}))})), ...collect(value.suites || [])]);
    report.cases = collect(result.suites);
    const database = await connection(deployment);
    try {
      await until(async () => {
        const active = await query(database, "SELECT COUNT(*) AS total FROM supportops.evaluation_experiments WHERE status IN ('QUEUED','PREPARING','RUNNING','CANCELLING')");
        const guard = await query(database, 'SELECT attempt_id FROM supportops.evaluation_runner_guard WHERE id=1');
        return Number(active[0].total) === 0 && guard[0].attempt_id === null;
      }, 120000, 'EVALUATION_CHILDREN_NOT_IDLE');
      report.cleanup.evaluation = 'IDLE';
      const attempts = await query(database, 'SELECT id,state,cleanup_status,recovery_status,resources_json,process_json FROM supportops.evaluation_attempts');
      await atomicJson(join(directory, 'evaluation-ownership-private.json'), attempts);
    } finally { await closeDatabase(database); }
    await atomicJson(join(directory, 'network-private.json'), proxy.records);
    await atomicJson(join(directory, 'fixture-requests-private.json'), provider.requests);
    report.fixtureRequests = provider.requests.reduce((counts, request) => ({...counts, [request.path]: (counts[request.path] || 0) + 1}), {});
    report.status = testExit ? 'FAIL' : 'PASS';
    return {directory, deployment, data, report, runtime, provider};
  } catch (error) { report.status = 'FAIL'; report.errorCode = /^[A-Z_]+$/.test(error.message) ? error.message : 'BROWSER_RUN_FAILED'; throw error; }
  finally {
    if (proxy) await proxy.close();
    if (runtime) { try { await runtime.stop(); report.cleanup.application = 'STOPPED_IDENTITY_CONFIRMED'; } catch { report.cleanup.application = 'UNKNOWN_RETAINED'; } }
    if (provider) await provider.close();
    if (deployment && report.cleanup.application === 'STOPPED_IDENTITY_CONFIRMED' && report.cleanup.evaluation === 'IDLE') {
      if (keep) report.cleanup.storage = 'RETAINED_FOR_RECOVERY';
      else { try { await cleanDeployment(deployment); report.cleanup.storage = 'CLEANED'; } catch { report.cleanup.storage = 'FAILED_RETAINED'; } }
    } else if (deployment) report.cleanup.storage = 'RETAINED_PENDING_IDENTITY';
    await atomicJson(join(directory, 'public/browser-report.json'), report);
    console.log(`Browser ${report.status}: ${join(directory, 'public/browser-report.json')}`);
  }
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    const input = options(process.argv.slice(2), ['directory', 'jar'], ['keep']);
    const result = await runBrowser({directory: input.directory, jar: input.jar, keep: input.keep});
    process.exitCode = result.report.status === 'PASS' && !Object.values(result.report.cleanup).some(value => value.includes('FAILED') || value.includes('UNKNOWN')) ? 0 : 1;
  } catch { console.error('BROWSER_RUN_FAILED'); process.exitCode = 1; }
}
