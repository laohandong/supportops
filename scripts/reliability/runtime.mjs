import {spawn} from 'node:child_process';
import {copyFile, readFile, access} from 'node:fs/promises';
import {createServer, connect} from 'node:net';
import {join, resolve, dirname} from 'node:path';
import {randomUUID} from 'node:crypto';
import {atomicJson, digest, javaExecutable, javaProbe, privateDirectory, execute, until, check} from './common.mjs';
import {configurationSecrets} from '../local-config.mjs';
import {redactor} from '../lib/runtime-safety.mjs';
import {maintenanceLease} from './maintenance.mjs';
import {registerRuntime} from './deployment.mjs';
import {configurationPort} from './port.mjs';

export async function availablePort(port) {
  return new Promise(done => {
    const server = createServer();
    server.once('error', () => done(false));
    server.listen(port, '127.0.0.1', () => server.close(() => done(true)));
  });
}

async function listening(port) {
  return new Promise(done => {
    const socket = connect({host: '127.0.0.1', port});
    socket.setTimeout(1000);
    socket.once('connect', () => { socket.destroy(); done(true); });
    socket.once('error', () => done(false));
    socket.once('timeout', () => { socket.destroy(); done(false); });
  });
}

/** 同时核对 OS 开始时间和真实回执，不按 PID 或文件日期猜测。 */
export async function inspect(record, {env = process.env, probe} = {}) {
  const result = (phase, status, code, exitCode) => ({schemaVersion: 1, runtimeId: record?.runtimeId, phase, checks: [check('identity', status, code)], exitCode});
  if (record?.schemaVersion !== 1 || !record.runtimeId || !record.jarSha256 || !record.jar || !record.javaExecutable || !Number.isSafeInteger(record.pid)) return result('UNKNOWN', 'UNKNOWN', 'LEGACY_OR_INCOMPLETE_RUNTIME', 3);
  let identity;
  try { identity = await (probe ?? (() => javaProbe(javaExecutable(env), record.jar, 'io.supportops.ops.ProcessProbe', [String(record.pid)], {env, timeout: 15000})))(); }
  catch { return result('UNKNOWN', 'UNKNOWN', 'PROCESS_NOT_READABLE', 3); }
  if (identity.state === 'EXITED' || identity.state === 'ALIVE' && record.processStartedAt && identity.processStartedAt && identity.processStartedAt !== record.processStartedAt) {
    return result(record.phase === 'FAILED' ? 'FAILED' : 'STOPPED', 'PASS', identity.state === 'EXITED' ? 'PROCESS_EXITED' : 'PID_REUSED', record.phase === 'FAILED' ? 1 : 0);
  }
  if (identity.state !== 'ALIVE' || !record.processStartedAt || !identity.processStartedAt || !identity.command) return result('UNKNOWN', 'UNKNOWN', 'PROCESS_IDENTITY_INCOMPLETE', 3);
  const actualCommand=resolve(identity.command),expectedCommand=resolve(record.javaExecutable);
  if ((process.platform==='win32'?actualCommand.toLowerCase()!==expectedCommand.toLowerCase():actualCommand!==expectedCommand) || identity.arguments && !identity.arguments.includes(record.jar)) return result('MISMATCH', 'FAIL', 'PROCESS_IDENTITY_MISMATCH', 1);
  try {
    if (await digest(record.jar) !== record.jarSha256) throw new Error('RUNTIME_JAR_CHANGED');
    const receipt = JSON.parse(await readFile(join(record.directory, 'ready.json'), 'utf8'));
    if (receipt.schemaVersion !== 1 || receipt.runtimeId !== record.runtimeId || receipt.pid !== record.pid
        || receipt.processStartedAt !== record.processStartedAt || receipt.jarSha256 !== record.jarSha256 || receipt.phase !== 'READY'
        || !receipt.applicationReadyAt || !Number.isInteger(receipt.port) || receipt.port < 1) throw new Error('READY_RECEIPT_MISMATCH');
    if (!await listening(receipt.port)) throw new Error('READY_PORT_NOT_LISTENING');
    return {...result('READY', 'PASS', 'OS_IDENTITY_AND_READY_MATCH', 0), port: receipt.port, jarSha256: record.jarSha256};
  } catch (error) {
    if (error.code === 'ENOENT' && record.phase === 'STARTING') return result('STARTING', 'UNKNOWN', 'READY_NOT_RECEIVED', 3);
    return result('UNKNOWN', 'UNKNOWN', 'READY_NOT_VERIFIABLE', 3);
  }
}

/** 登记前挂接退出监听；受控参数绑定 JAR、PID、runtimeId 和实际端口。 */
export async function launch({root, env, jar, runtimeRoot, cwd = root, settings, providers, port, deployment, applicationArgs = [], log = process.stdout, recordWriter = atomicJson}) {
  const java = javaExecutable(env);
  const version = await execute(java, ['-version'], {env, windowsHide: true, timeout: 10000});
  if (Number(/version "(\d+)/.exec(version.stderr + version.stdout)?.[1]) !== 21) throw new Error('JDK_21_REQUIRED');
  if (Number(process.versions.node.split('.')[0]) < 22) throw new Error('NODE_22_REQUIRED');
  const configuredPort = port === undefined ? await configurationPort({java, jar, env, cwd,
    args: [...(providers !== undefined ? [`--spring.config.import=${providers ? 'optional:file:' + providers.replaceAll('\\', '/') : ''}`] : []), ...applicationArgs]}) : port;
  if (!Number.isInteger(configuredPort) || configuredPort < 0 || configuredPort > 65535) throw new Error('PORT_INVALID');
  if (deployment) {
    await access(deployment);
    try { await access(join(dirname(deployment), 'maintenance.lock')); throw new Error('MAINTENANCE_LOCKED'); }
    catch (error) { if (error.code !== 'ENOENT') throw error; }
  }
  if (configuredPort && !await availablePort(configuredPort)) throw new Error('PORT_IN_USE');
  const runtimeId = randomUUID(), directory = join(runtimeRoot, runtimeId);
  await privateDirectory(directory);
  const copy = join(directory, 'application.jar');
  const record = {schemaVersion: 1, runtimeId, directory, javaExecutable: java, jar: copy, jarSha256: 'unknown',
    applicationVersion: 'unknown', buildRevision: 'unknown', sourceState: 'unknown', phase: 'STARTING', pid: null,
    processStartedAt: null, applicationReadyAt: null, port: configuredPort, exitCode: null};
  let saving = Promise.resolve();
  const save = () => {
    record.updatedAt = new Date().toISOString();
    const snapshot = structuredClone(record);
    // 就绪与退出可能同时发生；每个完整快照按产生顺序落盘，写失败仍传回对应调用方。
    const operation = saving.catch(() => {}).then(() => recordWriter(join(directory, 'runtime.json'), snapshot));
    saving = operation;
    return operation;
  };
  try { await copyFile(jar, copy); record.jarSha256 = await digest(copy); }
  catch { record.phase = 'FAILED'; record.reasonCode = 'JAR_COPY_FAILED'; await save(); throw new Error('JAR_COPY_FAILED'); }
  await save();
  let declared,releaseStart;
  if(deployment){
    declared=JSON.parse(await readFile(deployment,'utf8'));
    releaseStart=await maintenanceLease(declared,copy,'WRITER_START',runtimeId,env);
    // 进程创建前登记意图；即使启动器中断，备份也不能把未核查写入者视为不存在。
    await registerRuntime(declared,{directory,record});
  }
  const args = [`-Dsupportops.runtime.id=${runtimeId}`, `-Dsupportops.runtime.receipt=${join(directory, 'ready.json')}`,
    `-Dsupportops.runtime.jar=${copy}`, '-Dpdfbox.fontcache=.cache/pdfbox', '-jar', copy, ...(port === undefined ? [] : [`--server.port=${port}`]),
    ...(settings ? [`--supportops.settings.file=${settings}`] : []),
    ...(providers !== undefined ? [`--spring.config.import=${providers ? 'optional:file:' + providers.replaceAll('\\', '/') : ''}`] : []), ...applicationArgs];
  const child = spawn(java, args, {cwd, env, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe']});
  let closed = false, requestedStop = false, exitCode = null, signal = null;
  const completion = new Promise(done => {
    child.once('error', () => { closed = true; exitCode = 1; done(); });
    child.once('close', (code, exitSignal) => { closed = true; exitCode = code; signal = exitSignal; done(); });
  });
  const redact = redactor(configurationSecrets(env));
  for (const stream of [child.stdout, child.stderr]) {
    stream.setEncoding('utf8'); let pending = '';
    stream.on('data', chunk => {
      pending += chunk; let end;
      while ((end = pending.indexOf('\n')) >= 0) { log.write(redact(pending.slice(0, end + 1))); pending = pending.slice(end + 1); }
      if (pending.length > 1024 * 1024) { pending = ''; log.write('[LOG_LINE_LIMIT]\n'); }
    });
    stream.on('end', () => { if (pending) log.write(redact(pending)); });
  }
  record.pid = child.pid ?? null;
  if (record.pid) {
    try { const identity=await javaProbe(java, copy, 'io.supportops.ops.ProcessProbe', [String(record.pid)], {env, cwd, timeout: 15000}); record.processStartedAt=identity.processStartedAt; if(identity.command)record.javaExecutable=identity.command; }
    catch { record.identityCode = 'PROCESS_NOT_READABLE'; }
  }
  try { await save(); }
  catch {
    // 子进程已由本次启动创建，登记失败也必须有界结束它；停止或落盘未知时保留维护锁。
    if (!closed) child.kill('SIGTERM');
    await until(() => closed, 15000, 'REGISTRATION_STOP_UNCONFIRMED');
    record.phase = 'FAILED'; record.exitCode = exitCode; record.signal = signal; record.reasonCode = 'RUNTIME_REGISTRATION_FAILED';
    await save();
    if (releaseStart) await releaseStart();
    throw new Error('RUNTIME_REGISTRATION_FAILED');
  }
  const finish = (async () => {
    await completion;
    record.phase = requestedStop || exitCode === 0 ? 'STOPPED' : 'FAILED';
    record.exitCode = exitCode; record.signal = signal; await save();
  })();
  // 调用方稍后仍会等待并收到失败；避免启动尚未返回时退出写盘失败成为未处理拒绝。
  finish.catch(() => {});
  const stop = async () => {
    if (closed) { await finish; return; }
    const state = await inspect(record, {env});
    if (!['READY', 'STARTING'].includes(state.phase)) throw new Error('STOP_IDENTITY_UNCONFIRMED');
    requestedStop = true; child.kill('SIGTERM');
    await until(() => closed, 15000, 'STOP_TIMEOUT'); await finish;
  };
  try {
    await until(async () => {
      if (closed) throw new Error('APPLICATION_START_FAILED');
      try {
        const receipt = JSON.parse(await readFile(join(directory, 'ready.json'), 'utf8'));
        if (receipt.runtimeId !== runtimeId || receipt.jarSha256 !== record.jarSha256 || receipt.pid !== record.pid || !receipt.processStartedAt) throw new Error('READY_RECEIPT_MISMATCH');
        if (record.processStartedAt && record.processStartedAt !== receipt.processStartedAt) throw new Error('PROCESS_IDENTITY_MISMATCH');
        record.processStartedAt = receipt.processStartedAt;
        if (receipt.phase === 'READY') {
          if (closed) throw new Error('APPLICATION_EXITED_AFTER_READY');
          if (configuredPort && receipt.port !== configuredPort) throw new Error('READY_PORT_MISMATCH');
          Object.assign(record, {phase: 'READY', port: receipt.port, applicationVersion: receipt.applicationVersion, applicationReadyAt: receipt.applicationReadyAt});
          await save(); return true;
        }
      } catch (error) { if (error.code !== 'ENOENT') throw error; }
      return false;
    }, 90000, 'APPLICATION_READY_TIMEOUT');
    if (closed) throw new Error('APPLICATION_EXITED_AFTER_READY');
    if(declared)await registerRuntime(declared,{directory,record});
    if(releaseStart){const release=releaseStart;releaseStart=null;await release();}
  } catch (error) {
    if (!closed) { child.kill('SIGTERM'); await until(() => closed, 15000, 'START_FAILURE_STOP_UNCONFIRMED'); }
    await finish;
    if(releaseStart)await releaseStart();
    throw error;
  }
  return {record, directory, child, completion: finish, stop, base: `http://127.0.0.1:${record.port}`};
}
