import {spawn, execFile} from 'node:child_process';
import {copyFile, mkdir, writeFile} from 'node:fs/promises';
import {randomUUID} from 'node:crypto';
import {dirname, join, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {promisify} from 'node:util';
import {loadLocalConfig, configurationSecrets} from './local-config.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const args = process.argv.slice(2);
if (args.length > 1 || args.some(arg => !arg.startsWith('--config='))) throw new Error('Usage: node scripts/start.mjs [--config=path]');
const config = resolve(root, args[0]?.slice('--config='.length) || '.local/model.env');
const env = await loadLocalConfig(config);
try {
  const java = env.JAVA_HOME ? join(env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java';
  const version = await promisify(execFile)(java, ['-version'], {env, windowsHide: true, timeout: 10000});
  if (!(Number(/version "(\d+)/.exec(version.stderr || version.stdout)?.[1]) >= 21)) throw new Error('Set JAVA_HOME to JDK 21 or newer.');
  const runtime = join(root, '.local/runtime', `${Date.now()}-${randomUUID().slice(0, 8)}`);
  await mkdir(runtime, {recursive: true});
  const jar = join(runtime, 'application.jar');
  await copyFile(join(root, 'target/supportops-0.1.0-SNAPSHOT.jar'), jar);
  const child = spawn(java, ['-Dpdfbox.fontcache=.cache/pdfbox', '-jar', jar], {cwd: root, env, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe']});
  const lifecycle = {pid: child.pid, startedAt: new Date().toISOString(), jar};
  const secrets = configurationSecrets(env);
  for (const [source, destination] of [[child.stdout, process.stdout], [child.stderr, process.stderr]]) {
    source.setEncoding('utf8'); let pending = '';
    const emit = line => destination.write(secrets.reduce((text, key) => text.replaceAll(key, '[REDACTED]'), line));
    source.on('data', chunk => { pending += chunk; let end; while ((end = pending.indexOf('\n')) >= 0) { emit(pending.slice(0, end + 1)); pending = pending.slice(end + 1); } });
    source.on('end', () => { if (pending) emit(pending); });
  }
  const stop = () => { if (child.exitCode === null && child.signalCode === null) child.kill('SIGTERM'); };
  process.on('SIGINT', stop); process.on('SIGTERM', stop);
  const completion = new Promise(resolveExit => { child.on('error', () => resolveExit(1)); child.on('close', code => resolveExit(code ?? 0)); });
  await writeFile(join(runtime, 'runtime.json'), JSON.stringify(lifecycle, null, 2));
  process.exitCode = await completion;
  lifecycle.stoppedAt = new Date().toISOString(); lifecycle.exitCode = process.exitCode;
  await writeFile(join(runtime, 'runtime.json'), JSON.stringify(lifecycle, null, 2));
  process.off('SIGINT', stop); process.off('SIGTERM', stop);
} catch (error) {
  console.error(error.code === 'ENOENT' ? 'JDK or packaged JAR not found. Set JAVA_HOME and run Maven verify first.' : error.message);
  process.exitCode = 1;
}
