import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {createWriteStream} from 'node:fs';
import {readFile, writeFile, mkdir, readdir} from 'node:fs/promises';
import {createServer as tcpServer} from 'node:net';
import {createServer as httpServer} from 'node:http';
import {join, resolve} from 'node:path';
import {pathToFileURL} from 'node:url';
import {randomUUID} from 'node:crypto';
import {options, atomicJson, privateDirectory, execute, digest, until} from './reliability/common.mjs';
import {createDeployment, cleanDeployment, verifyDeployment} from './reliability/deployment.mjs';
import {availablePort, inspect} from './reliability/runtime.mjs';
import {databaseSnapshot, elasticSnapshot, es} from './reliability/storage.mjs';
import {backupPlan, makeBackup, restoreBackup, validateBackup, stoppedWriters, captureObjects} from './reliability/backup-v1.mjs';
import {systemEnvironment} from './lib/runtime-safety.mjs';

/** 有界执行真实 CLI，失败保留退出码及私有诊断，不回显配置。 */
async function command(root, script, args, env) {
  try {
    const result = await execute(process.execPath, [join(root, script), ...args], {cwd: root, env, windowsHide: true, timeout: 55000, maxBuffer: 4 * 1024 * 1024});
    return {exitCode: 0, output: result.stdout, error: result.stderr};
  } catch (error) {
    if (error.killed || typeof error.code !== 'number') throw new Error('COMMAND_NOT_BOUNDED');
    return {exitCode: error.code, output: error.stdout, error: error.stderr};
  }
}

/** 分配并释放真实回环端口；占用场景另外保留监听器直到断言完成。 */
async function listener(server) {
  await new Promise((done, reject) => {server.once('error', reject); server.listen(0, '127.0.0.1', done);});
  return server.address().port;
}
async function close(server) {
  server.closeAllConnections?.();
  await new Promise((done, reject) => server.close(error => error ? reject(error) : done()));
}
async function freePort() {
  const server = tcpServer(); const port = await listener(server); await close(server); return port;
}

/** 直接调用正式启动 CLI；IPC 仅发出正常退出信号，适用于 Windows 与 Linux。 */
async function startCase({root, directory, source, jar, env, config, port, expected}) {
  await privateDirectory(directory);
  const runtimeRoot = join(directory, 'runtime');
  const args = ['--deployment=' + join(source.directory, 'deployment.json'), '--jar=' + jar,
    '--runtime-root=' + runtimeRoot, '--providers=none', '--config=' + config, ...(port === undefined ? [] : ['--port=' + port])];
  const entry = pathToFileURL(join(root, 'scripts/start.mjs')).href;
  const wrapper = `import {start} from ${JSON.stringify(entry)}; process.on('message', message => {if(message==='stop')process.emit('SIGTERM');}); try {await start(process.argv.slice(1));} finally {if(process.connected)process.disconnect();}`;
  const child = spawn(process.execPath, ['--input-type=module', '-e', wrapper, '--', ...args], {cwd: root, env, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe', 'ipc']});
  const log = createWriteStream(join(directory, 'start-private.log'), {mode: 0o600});
  child.stdout.pipe(log, {end: false}); child.stderr.pipe(log, {end: false});
  let announced = false, announcement = '';
  child.stdout.on('data', bytes => {announcement += bytes.toString(); if (announcement.includes('READY runtimeId=')) announced = true; if (announcement.length > 1024 * 1024) announcement = announcement.slice(-1024);});
  let closed = false, exitCode;
  const completion = new Promise((done, reject) => {
    child.once('error', reject); child.once('close', code => {closed = true; exitCode = code; done();});
  });
  let record, recordPath;
  try {
    await until(async () => {
      if (closed) throw new Error('START_CLI_EXITED_BEFORE_READY');
      let names; try {names = await readdir(runtimeRoot);} catch (error) {if (error.code === 'ENOENT') return false; throw error;}
      for (const name of names) {
        recordPath = join(runtimeRoot, name, 'runtime.json');
        try {record = JSON.parse(await readFile(recordPath, 'utf8'));} catch (error) {if (error.code === 'ENOENT') continue; throw error;}
        if (record.phase === 'FAILED') throw new Error('START_CLI_FAILED');
        if (record.phase === 'READY' && announced) return true;
      }
      return false;
    }, 110000, 'START_CLI_READY_TIMEOUT');
    assert.ok(record.port > 0);
    if (expected) assert.equal(record.port, expected);
    const receipt = JSON.parse(await readFile(join(record.directory, 'ready.json'), 'utf8'));
    assert.equal(receipt.port, record.port); assert.equal(record.jarSha256, await digest(jar));
    assert.equal((await inspect(record, {env})).phase, 'READY');
    const response = await fetch(`http://127.0.0.1:${record.port}/api/auth/setup`, {signal: AbortSignal.timeout(5000)});
    assert.equal(response.status, 200);
    assert.equal((await response.json()).setupRequired, true);
    return {port: record.port, receiptPort: receipt.port, runtimeId: record.runtimeId, pid: record.pid, httpStatus: response.status};
  } finally {
    if (!closed && child.connected) child.send('stop');
    await until(() => closed, 30000, 'START_CLI_STOP_TIMEOUT'); await completion; log.end();
    assert.equal(exitCode, 0);
    if (recordPath) {
      record = JSON.parse(await readFile(recordPath, 'utf8'));
      assert.equal(record.phase, 'STOPPED'); assert.equal((await inspect(record, {env})).phase, 'STOPPED');
    }
  }
}

/** 在新建专属后端上验证三项独立验收边界，不复用当前工作台。 */
export async function runBoundaries({directory, jar, root = process.cwd()} = {}) {
  directory = resolve(directory || join(root, '.local/reliability-boundaries', randomUUID()));
  jar = resolve(jar || join(root, 'target/supportops-0.1.0-SNAPSHOT.jar'));
  await privateDirectory(directory); await mkdir(join(directory, 'public'));
  const report = {schemaVersion: 1, platform: process.platform, node: process.version, jarSha256: await digest(jar), status: 'RUNNING', cases: [], cleanup: []};
  const env = systemEnvironment();
  let source, sourceConfig;
  const targets = [];
  const caseConfig = async (name, values = {}) => {
    const path = join(directory, name + '.env');
    const keys = new Set(Object.keys(values));
    const text = sourceConfig.split('\n').filter(line => !keys.has(line.split('=')[0])).join('\n');
    await writeFile(path, text + '\n' + Object.entries(values).map(([key, value]) => key + '=' + value).join('\n') + '\n', {mode: 0o600, flag: 'wx'});
    return path;
  };
  const doctorArgs = config => ['--config=' + config, '--jar=' + jar, '--workdir=' + source.directory, '--settings=' + source.settings, '--providers=none'];
  const run = async (id, task) => {
    try {report.cases.push({id, status: 'PASS', proof: await task()});}
    catch (error) {
      report.cases.push({id, status: 'FAIL', errorCode: /^[A-Z_]+$/.test(error.message) ? error.message : 'ASSERTION_FAILED'});
      await atomicJson(join(directory, id + '-failure-private.json'), {message: error.message, stack: error.stack});
    }
    await atomicJson(join(directory, 'public/boundaries-report.json'), report);
  };
  const start = (name, config, environment, port, expected) => startCase({root, directory: join(directory, name), source, jar, env: environment, config, port, expected});
  const doctorPort = async (config, environment, expected, explicit) => {
    const result = await command(root, 'scripts/doctor.mjs', [...doctorArgs(config), ...(explicit === undefined ? [] : ['--port=' + explicit]), '--json'], environment);
    assert.equal(result.exitCode, 0);
    const check = JSON.parse(result.output).checks.find(item => item.id === 'port');
    assert.equal(check.status, 'PASS'); assert.equal(check.code, expected === 0 ? 'AUTOMATIC_PORT_EXPLICIT' : 'PORT_AVAILABLE');
  };
  try {
    source = await createDeployment(root, join(directory, 'source'));
    sourceConfig = await readFile(source.config, 'utf8');
    await run('RD-F03-ENV', async () => {
      const port = await freePort(), environment = {...env, PORT: String(port)};
      await doctorPort(source.config, environment, port);
      return start('port-env', source.config, environment, undefined, port);
    });
    await run('RD-F03-FILE', async () => {
      const port = await freePort(), config = await caseConfig('port-file', {PORT: port});
      const environment = {...env, PORT: String(await freePort())};
      await doctorPort(config, environment, port);
      return start('port-file-run', config, environment, undefined, port);
    });
    await run('RD-F03-EXPLICIT', async () => {
      const blocked = tcpServer(), occupied = await listener(blocked), port = await freePort();
      const environment = {...env, PORT: String(occupied)};
      try {
        await doctorPort(source.config, environment, port, port);
        const proof = await start('port-explicit', source.config, environment, port, port);
        assert.equal(blocked.listening, true); return {...proof, configuredPortOccupied: true};
      } finally {await close(blocked);}
    });
    await run('RD-F03-DEFAULT', async () => {
      if (!await availablePort(18080)) throw new Error('DEFAULT_PORT_OCCUPIED_UNRELATED');
      await doctorPort(source.config, env, 18080);
      return start('port-default', source.config, env, undefined, 18080);
    });
    await run('RD-F03-ZERO', async () => {
      await doctorPort(source.config, env, 0, 0);
      const explicit = await start('port-zero', source.config, env, 0);
      const configured = await start('port-env-zero', source.config, {...env, PORT: '0'});
      return {explicit, configured};
    });
    await run('RD-F03-INVALID', async () => {
      const results = [];
      for (const value of ['-1', '65536', 'synthetic-never-print']) {
        for (const script of ['scripts/start.mjs', 'scripts/doctor.mjs']) {
          const explicit = await command(root, script, [...doctorArgs(source.config), '--port=' + value, ...(script.includes('doctor') ? ['--json'] : [])], env);
          assert.equal(explicit.exitCode, 2); assert.ok(!(explicit.output + explicit.error).includes(value));
          results.push({mode: 'explicit', script, exitCode: explicit.exitCode});
          const configured = await command(root, script, [...doctorArgs(source.config), ...(script.includes('doctor') ? ['--json'] : [])], {...env, PORT: value});
          assert.equal(configured.exitCode, 2); assert.ok(!(configured.output + configured.error).includes(value));
          results.push({mode: 'environment', script, exitCode: configured.exitCode});
        }
      }
      return {results, invalidValuesNotPrinted: true};
    });
    await run('RD-F03-BUSY', async () => {
      const server = tcpServer(), port = await listener(server);
      try {
        const environment = {...env, PORT: String(port)};
        const started = await command(root, 'scripts/start.mjs', doctorArgs(source.config), environment);
        assert.equal(started.exitCode, 1); assert.ok(started.error.includes('PORT_IN_USE'));
        const json = await command(root, 'scripts/doctor.mjs', [...doctorArgs(source.config), '--json'], environment);
        const text = await command(root, 'scripts/doctor.mjs', doctorArgs(source.config), environment);
        assert.equal(json.exitCode, 1); assert.equal(text.exitCode, 1);
        assert.deepEqual(JSON.parse(json.output).checks.find(item => item.id === 'port'), {id: 'port', status: 'FAIL', code: 'PORT_IN_USE', scope: 'LOCAL'});
        assert.ok(text.output.includes('PORT_IN_USE')); assert.equal(server.listening, true);
        return {port, startExit: 1, doctorExit: 1, listenerPreserved: true, textJsonSame: true};
      } finally {await close(server);}
    });
    await run('RD-F02', async () => {
      const snapshot = async () => ({mysql: await databaseSnapshot(source), minio: await captureObjects(source), elastic: await elasticSnapshot(source)});
      const before = await snapshot(), health = await es(source, 'GET', '/_cluster/health');
      const actual = await command(root, 'scripts/doctor.mjs', [...doctorArgs(source.config), '--port=0', '--json'], env);
      assert.equal(actual.exitCode, 0);
      assert.equal(JSON.parse(actual.output).checks.find(item => item.id === 'elasticsearch').status, 'PASS');
      const variants = [
        {id: 'empty', body: {}}, {id: 'missing', body: {status: 'green'}}, {id: 'unknown', body: {...health, status: 'unknown'}},
        {id: 'null-status', body: {...health, status: null}}, {id: 'null-root', body: null}, {id: 'red', body: {...health, status: 'red'}},
        {id: 'timed-out', body: {...health, timed_out: true}}, {id: 'malformed', raw: '{'}, {id: 'http503', body: health, status: 503}, {id: 'timeout', hang: true}
      ];
      const results = [], requests = [];
      for (const variant of variants) {
        const server = httpServer((request, response) => {
          requests.push({method: request.method, path: request.url});
          if (variant.hang) return;
          response.writeHead(variant.status || 200, {'Content-Type': 'application/json'});
          response.end(variant.raw === undefined ? JSON.stringify(variant.body) : variant.raw);
        });
        const port = await listener(server), config = await caseConfig('health-' + variant.id, {SUPPORTOPS_ES_ENDPOINT: 'http://127.0.0.1:' + port});
        try {
          const args = [...doctorArgs(config), '--port=0'];
          const json = await command(root, 'scripts/doctor.mjs', [...args, '--json'], env);
          const text = await command(root, 'scripts/doctor.mjs', args, env);
          const parsed = JSON.parse(json.output), check = parsed.checks.find(item => item.id === 'elasticsearch');
          assert.equal(json.exitCode, 1); assert.equal(text.exitCode, 1); assert.equal(parsed.exitCode, 1);
          assert.equal(check.status, 'FAIL'); assert.equal(check.code, 'ES_UNAVAILABLE');
          for (const item of parsed.checks) assert.ok(text.output.includes(item.code));
          results.push({id: variant.id, status: check.status, code: check.code, exitCode: json.exitCode, textJsonSame: true});
        } finally {await close(server);}
      }
      const after = await snapshot(); assert.deepEqual(after, before);
      assert.equal(requests.length, variants.length * 2);
      assert.ok(requests.every(item => item.method === 'GET' && item.path === '/_cluster/health?timeout=4s'));
      await atomicJson(join(directory, 'health-readonly-private.json'), {before, after, health, requests});
      return {actualCluster: health.status, invalidResponses: results, wholeRowsStructuresObjectsMappingsUnchanged: true, healthOnlyRequests: requests.length, modelRequests: 0};
    });
    await run('RD-F01', async () => {
      const current = JSON.parse(await readFile(join(source.directory, 'deployment.json'), 'utf8')); Object.assign(source, current);
      const index = 'supportops-boundary-' + randomUUID().slice(0, 8);
      await es(source, 'PUT', '/' + index, {mappings: {properties: {ordinal: {type: 'integer'}, marker: {type: 'keyword'}}}});
      const backup = async name => {
        const plan = join(directory, name + '-plan.json'), output = join(directory, name);
        await backupPlan(source, plan);
        return {output, manifest: await makeBackup(plan, source.deploymentId, output)};
      };
      assert.equal((await es(source, 'GET', '/' + index + '/_count')).count, 0);
      assert.equal((await elasticSnapshot(source)).indices[index].count, 0);
      const empty = await backup('backup-empty'); assert.equal(empty.manifest.status, 'COMPLETE'); assert.equal(empty.manifest.elastic.indices[index].count, 0);
      for (let offset = 0; offset < 10000; offset += 1000) {
        const lines = [];
        for (let ordinal = offset; ordinal < offset + 1000; ordinal++) {
          lines.push(JSON.stringify({index: {_index: index, _id: 'rd-' + String(ordinal).padStart(5, '0')}}), JSON.stringify({ordinal, marker: 'baseline'}));
        }
        const response = await fetch(`http://127.0.0.1:${source.ports.elasticsearch}/_bulk?refresh=true`, {
          method: 'POST', headers: {'Content-Type': 'application/x-ndjson'}, body: lines.join('\n') + '\n', signal: AbortSignal.timeout(30000)});
        assert.equal(response.status, 200); assert.equal((await response.json()).errors, false);
      }
      assert.equal((await es(source, 'GET', '/' + index + '/_count')).count, 10000);
      const baseline = await elasticSnapshot(source); assert.equal(baseline.indices[index].count, 10000);
      const full = await backup('backup-limit'); assert.equal(full.manifest.status, 'COMPLETE'); assert.deepEqual(full.manifest.elastic, baseline);
      const targetPath = join(directory, 'restored-limit');
      try {
        const restored = await restoreBackup(root, full.output, targetPath); targets.push(restored.deployment);
        assert.equal(restored.progress.status, 'RESTORED_STOPPED'); assert.deepEqual(await elasticSnapshot(restored.deployment), baseline);
        assert.equal((await es(restored.deployment, 'GET', '/' + index + '/_count')).count, 10000);
      } catch (error) {
        try {const target = JSON.parse(await readFile(join(targetPath, 'deployment.json'), 'utf8')); if (!targets.some(item => item.deploymentId === target.deploymentId)) targets.push(target);} catch {}
        throw error;
      }
      assert.deepEqual(await elasticSnapshot(source), baseline);
      await es(source, 'PUT', '/' + index + '/_doc/rd-09999?refresh=true', {ordinal: 9999, marker: 'changed-tail'});
      const changed = await elasticSnapshot(source);
      assert.equal(changed.indices[index].count, 10000); assert.notEqual(changed.indices[index].sha256, baseline.indices[index].sha256);
      await es(source, 'PUT', '/' + index + '/_doc/rd-09999?refresh=true', {ordinal: 9999, marker: 'baseline'});
      assert.deepEqual(await elasticSnapshot(source), baseline);
      await es(source, 'PUT', '/' + index + '/_doc/rd-10000?refresh=true', {ordinal: 10000, marker: 'over-limit'});
      const actual = (await es(source, 'GET', '/' + index + '/_count')).count; assert.equal(actual, 10001);
      const legacyTotal = (await es(source, 'POST', '/' + index + '/_search', {size: 10000, query: {match_all: {}}})).hits.total;
      assert.deepEqual(legacyTotal, {value: 10000, relation: 'gte'});
      await assert.rejects(elasticSnapshot(source), /BACKUP_INDEX_SIZE_LIMIT/);
      const rejectedPath = join(directory, 'backup-over-limit'), plan = join(directory, 'backup-over-limit-plan.json');
      await backupPlan(source, plan); await assert.rejects(makeBackup(plan, source.deploymentId, rejectedPath), /BACKUP_INDEX_SIZE_LIMIT/);
      const rejected = JSON.parse(await readFile(join(rejectedPath, 'manifest.json'), 'utf8'));
      assert.equal(rejected.status, 'FAILED'); assert.equal(rejected.errorCode, 'BACKUP_INDEX_SIZE_LIMIT'); assert.equal(rejected.maintenanceLock, 'RETAINED');
      await assert.rejects(validateBackup(rejectedPath), /BACKUP_MANIFEST_UNSUPPORTED/);
      await atomicJson(join(directory, 'index-boundary-private.json'), {index, baseline, changed, actual, legacyTotal, backupIds: [empty.manifest.backupId, full.manifest.backupId], rejected});
      return {counts: [0, 10000, actual], limit: 10000, emptyBackup: 'COMPLETE', limitBackup: 'COMPLETE', limitRestore: 'RESTORED_STOPPED', tailChangeDetected: true, overLimitBackup: rejected.status, errorCode: rejected.errorCode, legacyTotal, failedBackupRestoreRejected: true};
    });
  } catch (error) {
    report.errorCode = /^[A-Z_]+$/.test(error.message) ? error.message : 'BOUNDARY_PREPARATION_FAILED';
    await atomicJson(join(directory, 'runner-failure-private.json'), {message: error.message, stack: error.stack});
  } finally {
    for (const target of targets) {
      try {await verifyDeployment(target); assert.equal(target.processes.length, 0); await cleanDeployment(target); report.cleanup.push({deploymentId: target.deploymentId, status: 'CLEANED_OWNED_RESOURCES'});}
      catch {report.cleanup.push({deploymentId: target.deploymentId, status: 'UNKNOWN_RETAINED'});}
    }
    if (source) {
      try {
        Object.assign(source, JSON.parse(await readFile(join(source.directory, 'deployment.json'), 'utf8')));
        // 未登记任何启动意图时没有应用进程；已登记场景必须通过完整停写证明。
        if (source.processes.length) await stoppedWriters(source);
        await cleanDeployment(source); report.cleanup.push({deploymentId: source.deploymentId, status: 'CLEANED_OWNED_RESOURCES'});
      } catch {report.cleanup.push({deploymentId: source.deploymentId, status: 'UNKNOWN_RETAINED'});}
    } else {
      try {const partial = JSON.parse(await readFile(join(directory, 'source/deployment.json'), 'utf8')); report.cleanup.push({deploymentId: partial.deploymentId, status: 'UNKNOWN_RETAINED'});} catch {}
    }
    for (const id of ['RD-F03-ENV', 'RD-F03-FILE', 'RD-F03-EXPLICIT', 'RD-F03-DEFAULT', 'RD-F03-ZERO', 'RD-F03-INVALID', 'RD-F03-BUSY', 'RD-F02', 'RD-F01']) {
      if (!report.cases.some(item => item.id === id)) report.cases.push({id, status: 'NOT_RUN', errorCode: 'PREPARATION_OR_DEPENDENCY_FAILED'});
    }
    report.status = !report.errorCode && report.cases.every(item => item.status === 'PASS') && report.cleanup.every(item => item.status === 'CLEANED_OWNED_RESOURCES') ? 'PASS' : 'FAIL';
    await atomicJson(join(directory, 'public/boundaries-report.json'), report);
  }
  return report;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {const result = await runBoundaries(options(process.argv.slice(2), ['directory', 'jar'])); console.log('Boundaries ' + result.status); process.exitCode = result.status === 'PASS' ? 0 : 1;}
  catch {console.error('BOUNDARY_TEST_FAILED'); process.exitCode = 1;}
}
