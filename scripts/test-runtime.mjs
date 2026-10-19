import assert from 'node:assert/strict';
import {readFile, writeFile, mkdir} from 'node:fs/promises';
import {createWriteStream} from 'node:fs';
import {createServer as tcpServer} from 'node:net';
import {createServer as httpServer} from 'node:http';
import {join, resolve} from 'node:path';
import {pathToFileURL} from 'node:url';
import {randomUUID} from 'node:crypto';
import {options, atomicJson, privateDirectory, execute, digest, files, until} from './reliability/common.mjs';
import {launch, inspect} from './reliability/runtime.mjs';
import {verifyDeployment, registerRuntime} from './reliability/deployment.mjs';
import {databaseSnapshot, elasticSnapshot} from './reliability/storage.mjs';
import {captureObjects} from './reliability/backup-v1.mjs';
import {loadLocalConfig} from './local-config.mjs';
import {systemEnvironment} from './lib/runtime-safety.mjs';

async function command(script, args, env, cwd = process.cwd()) {
  try { const value = await execute(process.execPath, [resolve(script), ...args], {env, cwd, windowsHide: true, timeout: 55000, maxBuffer: 4 * 1024 * 1024}); return {exitCode: 0, output: value.stdout}; }
  catch (error) { if (error.killed || typeof error.code !== 'number') throw new Error('COMMAND_NOT_BOUNDED'); return {exitCode: error.code, output: error.stdout}; }
}
async function config(path, env) { await writeFile(path, Object.entries(env).filter(([key]) => key.startsWith('SUPPORTOPS_')).map(([key, value]) => key + '=' + value).join('\n') + '\n', {mode: 0o600}); }

/** 独立 OS/HTTP 探针和真实存储；无法制造的 OS 权限拒绝单独注明协议注入。 */
export async function runRuntime({deployment, directory, jar, root = process.cwd()} = {}) {
  if (typeof deployment === 'string') deployment = JSON.parse(await readFile(resolve(deployment), 'utf8'));
  await verifyDeployment(deployment);
  directory = resolve(directory || join(root, '.local/reliability-runtime', randomUUID()));
  await privateDirectory(directory); await mkdir(join(directory, 'public'));
  jar = resolve(jar || join(root, 'target/supportops-0.1.0-SNAPSHOT.jar'));
  const env = await loadLocalConfig(deployment.config, systemEnvironment());
  env.SUPPORTOPS_EVALUATION_ENABLED = 'false'; env.SUPPORTOPS_FEISHU_ENABLED = 'false';
  const checks = [], report = {schemaVersion: 1, platform: process.platform, node: process.version, jarSha256: await digest(jar), cases: checks};
  let runtime;
  const run = async (id, task) => { try { const proof = await task(); checks.push({id, status: 'PASS', proof}); } catch (error) { checks.push({id, status: 'FAIL', errorCode: /^[A-Z_]+$/.test(error.message) ? error.message : 'ASSERTION_FAILED'}); await atomicJson(join(directory, id + '-failure-private.json'), {message: error.message, stack: error.stack}); } await atomicJson(join(directory, 'public/runtime-report.json'), report); };
  const launchApp = async (extra = [], overrides = {}) => {
    const log = createWriteStream(join(directory, 'application-' + randomUUID() + '.log'));
    return launch({root, env, jar, runtimeRoot: join(directory, 'runtime'), cwd: deployment.directory, port: 0, settings: deployment.settings,
      providers: '', deployment: join(deployment.directory, 'deployment.json'), applicationArgs: ['--spring.config.location=classpath:application.yml', '--supportops.knowledge.jobs.enabled=false', ...extra], log, ...overrides});
  };
  try {
    await run('D02', async () => {
      runtime = await launchApp(); await registerRuntime(deployment, runtime);
      assert.equal((await inspect(runtime.record, {env})).phase, 'READY');
      assert.equal(await digest(runtime.record.jar), report.jarSha256); assert.ok(runtime.record.applicationReadyAt);
      const ready = {...runtime.record}; await runtime.stop(); assert.equal((await inspect(runtime.record, {env})).phase, 'STOPPED');
      const before = await files(join(directory, 'runtime'));
      await assert.rejects(launchApp(['--spring.config.location=file:' + join(directory, 'missing-config.yml')]), /APPLICATION_START_FAILED/);
      const failedPath = (await files(join(directory, 'runtime'))).find(file => file.endsWith('/runtime.json') && !before.includes(file));
      const failed = JSON.parse(await readFile(join(directory, 'runtime', failedPath), 'utf8')); assert.equal(failed.phase, 'FAILED'); assert.equal(failed.applicationReadyAt, null);
      // 只注入一次身份记录写盘故障，应用仍为真实子 JVM；独立 OS 探针确认没有遗留写入者。
      let registrationPath, registrationWriteFailed = false;
      await assert.rejects(launchApp([], {recordWriter: async (path, value) => {
        if (value.pid && value.phase === 'STARTING' && !registrationWriteFailed) {
          registrationWriteFailed = true; registrationPath = path; throw new Error('INJECTED_RECORD_WRITE_FAILURE');
        }
        await atomicJson(path, value);
      }}), /RUNTIME_REGISTRATION_FAILED/);
      assert.equal(registrationWriteFailed, true);
      const registrationFailure = JSON.parse(await readFile(registrationPath, 'utf8'));
      assert.equal(registrationFailure.phase, 'FAILED'); assert.equal(registrationFailure.reasonCode, 'RUNTIME_REGISTRATION_FAILED');
      assert.ok(registrationFailure.pid > 0); assert.ok(registrationFailure.processStartedAt); assert.equal(registrationFailure.applicationReadyAt, null);
      const registrationState = await inspect(registrationFailure, {env});
      assert.equal(registrationState.phase, 'FAILED'); assert.equal(registrationState.checks[0].code, 'PROCESS_EXITED');
      await assert.rejects(readFile(join(deployment.directory, 'maintenance.lock')), {code: 'ENOENT'});
      runtime = await launchApp(); await registerRuntime(deployment, runtime); const unexpected = {...runtime.record};
      runtime.child.kill('SIGKILL'); await runtime.completion; assert.equal(runtime.record.phase, 'FAILED');
      runtime = await launchApp(); await registerRuntime(deployment, runtime);
      return {normalReady: {runtimeId: ready.runtimeId, pid: ready.pid, sha256: ready.jarSha256}, stopped: true, earlyFailure: failed.phase,
        registrationFailure: {fault: 'SINGLE_RECORD_WRITE_FAILURE_INJECTION', runtimeId: registrationFailure.runtimeId, pid: registrationFailure.pid, phase: registrationFailure.phase, osCode: registrationState.checks[0].code, leaseReleased: true}, unexpectedExit: unexpected.runtimeId};
    });
    await run('D03', async () => {
      assert.ok(runtime); const original = {...runtime.record};
      const reused = await inspect({...original, processStartedAt: '1970-01-01T00:00:00Z'}, {env}); assert.equal(reused.checks[0].code, 'PID_REUSED'); assert.equal(reused.phase, 'STOPPED');
      const unknown = await inspect(original, {probe: async () => ({state: 'UNKNOWN'})}); assert.equal(unknown.phase, 'UNKNOWN'); assert.equal(unknown.exitCode, 3);
      const legacy = await inspect({pid: original.pid}, {env}); assert.equal(legacy.phase, 'UNKNOWN'); assert.equal(legacy.exitCode, 3);
      const broken = join(directory, 'broken-runtime.json'); await writeFile(broken, '{'); assert.equal((await command('scripts/status.mjs', ['--runtime=' + broken, '--json'], env)).exitCode, 2);
      const blocked = tcpServer(); await new Promise(done => blocked.listen(0, '127.0.0.1', done)); const port = blocked.address().port;
      await assert.rejects(launch({root, env, jar, runtimeRoot: join(directory, 'blocked'), cwd: deployment.directory, port, settings: deployment.settings, log: createWriteStream(join(directory, 'blocked.log'))}), /PORT_IN_USE/);
      assert.equal(blocked.listening, true); await new Promise(done => blocked.close(done));
      assert.equal((await inspect(original, {env})).phase, 'READY');
      return {actualLiveIdentityMismatch: reused.checks[0].code, unreadableProtocolInjection: unknown.phase, legacy: legacy.phase, truncatedInputExit: 2, occupiedPort: port, existingProcessUntouched: true};
    });
    const doctorArgs = ['--config=' + deployment.config, '--jar=' + jar, '--workdir=' + deployment.directory, '--settings=' + deployment.settings, '--providers=none', '--port=0'];
    await run('D04', async () => {
      const started = Date.now(), noJdk = await command('scripts/doctor.mjs', [...doctorArgs, '--json'], {...env, JAVA_HOME: join(directory, 'missing-jdk')});
      assert.equal(noJdk.exitCode, 1); assert.ok(JSON.parse(noJdk.output).checks.some(check => check.code === 'JDK_UNAVAILABLE'));
      const noJar = await command('scripts/doctor.mjs', [...doctorArgs.filter(value => !value.startsWith('--jar=')), '--jar=' + join(directory, 'missing.jar'), '--json'], env);
      assert.equal(noJar.exitCode, 1); assert.ok(JSON.parse(noJar.output).checks.some(check => check.code === 'JAR_UNAVAILABLE'));
      const invalid = join(directory, 'invalid.env'); await writeFile(invalid, 'UNRECOGNIZED=synthetic-must-never-print\n');
      const bad = await command('scripts/doctor.mjs', ['--config=' + invalid, '--json'], env); assert.equal(bad.exitCode, 2); assert.ok(!bad.output.includes('synthetic-must-never-print'));
      const hanging = httpServer(() => {}); await new Promise(done => hanging.listen(0, '127.0.0.1', done));
      const timed = {...env, SUPPORTOPS_DB_URL: 'jdbc:mysql://127.0.0.1:1/supportops', SUPPORTOPS_MINIO_ENDPOINT: `http://127.0.0.1:${hanging.address().port}`, SUPPORTOPS_ES_ENDPOINT: `http://127.0.0.1:${hanging.address().port}`, SUPPORTOPS_API_KEY: 'synthetic-redaction-check-secret'};
      const timeoutConfig = join(directory, 'timeout.env'); await config(timeoutConfig, timed);
      let json, text;
      try { json = await command('scripts/doctor.mjs', [...doctorArgs.filter(value => !value.startsWith('--config=')), '--config=' + timeoutConfig, '--json'], env);
        text = await command('scripts/doctor.mjs', [...doctorArgs.filter(value => !value.startsWith('--config=')), '--config=' + timeoutConfig], env); }
      finally { hanging.closeAllConnections(); await new Promise(done => hanging.close(done)); }
      const parsed = JSON.parse(json.output); assert.equal(parsed.exitCode, 1); assert.equal(text.exitCode, parsed.exitCode);
      for (const check of parsed.checks) assert.ok(text.output.includes(check.code));
      assert.ok(!json.output.includes(timed.SUPPORTOPS_API_KEY)); assert.ok(!text.output.includes(timed.SUPPORTOPS_API_KEY));
      assert.ok(parsed.checks.filter(check => ['mysql','minio','elasticsearch'].includes(check.id)).every(check => check.status === 'FAIL'));
      return {missingJdk: noJdk.exitCode, missingJar: noJar.exitCode, invalidConfiguration: bad.exitCode, timeoutCodes: parsed.checks.map(check => check.code), durationMs: Date.now() - started, textJsonSame: true, secretsPrinted: false};
    });
    await run('D05', async () => {
      let received = 0; const fixture = httpServer((request, response) => { received++; response.writeHead(500).end(); });
      await new Promise(done => fixture.listen(0, '127.0.0.1', done));
      const fileConfig = join(directory, 'no-model.env'), settings = join(directory, 'ui-settings.json');
      await config(fileConfig, {...env, SUPPORTOPS_MODEL_PROVIDER: 'custom', SUPPORTOPS_MODEL: 'no-key', SUPPORTOPS_API_KEY: '', SUPPORTOPS_MODEL_BASE_URL: `http://127.0.0.1:${fixture.address().port}/v1`});
      const empty = join(directory, 'empty-settings.json'); await atomicJson(empty, {format: 1, roles: {}});
      const args = doctorArgs.filter(value => !value.startsWith('--config=') && !value.startsWith('--settings='));
      try {
        const missing = JSON.parse((await command('scripts/doctor.mjs', [...args, '--config=' + fileConfig, '--settings=' + empty, '--json'], systemEnvironment())).output);
        assert.equal(missing.exitCode, 0); assert.ok(missing.checks.some(check => check.id === 'model' && check.status === 'WARN' && check.scope === 'FILE_ENV'));
        await atomicJson(settings, {format: 1, roles: {model: {provider: 'custom', name: 'ui-model', 'base-url': `http://127.0.0.1:${fixture.address().port}/v1`, 'api-key': 'synthetic-ui-override-secret'}}});
        const override = JSON.parse((await command('scripts/doctor.mjs', [...args, '--config=' + fileConfig, '--settings=' + settings, '--json'], systemEnvironment())).output);
        assert.equal(override.exitCode, 0); assert.ok(override.checks.some(check => check.id === 'model' && check.scope === 'UI_OVERRIDE' && check.code === 'MODEL_AUTH_NOT_VERIFIED'));
        assert.ok(override.checks.some(check => check.id === 'docker' && check.status === 'NOT_APPLICABLE')); assert.equal(received, 0);
        return {missingModelIsWarning: true, uiOverride: true, docker: 'NOT_APPLICABLE', actualModelHttpRequests: received};
      } finally { fixture.closeAllConnections(); await new Promise(done => fixture.close(done)); }
    });
    await run('D06', async () => {
      const before = {mysql: await databaseSnapshot(deployment), minio: await captureObjects(deployment), elastic: await elasticSnapshot(deployment), descriptor: await digest(join(deployment.directory, 'deployment.json'))};
      const result = JSON.parse((await command('scripts/doctor.mjs', [...doctorArgs, '--deployment=' + join(deployment.directory, 'deployment.json'), '--json'], env)).output); assert.equal(result.exitCode, 0);
      const after = {mysql: await databaseSnapshot(deployment), minio: await captureObjects(deployment), elastic: await elasticSnapshot(deployment), descriptor: await digest(join(deployment.directory, 'deployment.json'))};
      assert.deepEqual(after, before); await atomicJson(join(directory, 'doctor-storage-private.json'), {before, after, result});
      return {wholeRowHashesEqual: true, tableStructuresEqual: true, objectHashesEqual: true, mappingAliasesEqual: true, descriptorUnchanged: true, apiPermissions: 'E01 actual browser/HTTP evidence'};
    });
  } finally { if (runtime) { try { await runtime.stop(); report.applicationCleanup = 'STOPPED_IDENTITY_CONFIRMED'; } catch { report.applicationCleanup = 'UNKNOWN_RETAINED'; } } await atomicJson(join(directory, 'public/runtime-report.json'), report); }
  report.status = checks.every(check => check.status === 'PASS') ? 'PASS' : 'FAIL'; await atomicJson(join(directory, 'public/runtime-report.json'), report); return report;
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try { const input = options(process.argv.slice(2), ['deployment','directory','jar']); const result = await runRuntime(input); console.log('Runtime ' + result.status); process.exitCode = result.status === 'PASS' ? 0 : 1; }
  catch { console.error('RUNTIME_TEST_FAILED'); process.exitCode = 1; }
}
