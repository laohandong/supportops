import {spawn, execFile} from 'node:child_process';
import {createHash, randomUUID} from 'node:crypto';
import {createWriteStream} from 'node:fs';
import {copyFile, mkdir, readFile, writeFile} from 'node:fs/promises';
import {dirname, join, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {promisify} from 'node:util';
import {loadLocalConfig, configurationSecrets} from './local-config.mjs';
import {evaluate} from '../evaluation/run.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const args = process.argv.slice(2);
const help = 'Usage: node scripts/evaluate.mjs [--sources-only | --hybrid | --compare] [--config=path]';
if (args.includes('--help')) { console.log(help); process.exit(0); }
if (args.filter(arg => ['--sources-only', '--hybrid', '--compare'].includes(arg)).length > 1 || args.filter(arg => arg.startsWith('--config=')).length > 1 || args.some(arg => !['--sources-only', '--hybrid', '--compare'].includes(arg) && !arg.startsWith('--config='))) throw new Error(help);
const sourcesOnly = args.includes('--sources-only');
const compare = args.includes('--compare');
const configPath = resolve(root, args.find(arg => arg.startsWith('--config='))?.slice('--config='.length) || '.local/model.env');
const env = await loadLocalConfig(configPath);
const id = `${Date.now()}-${randomUUID().slice(0, 8)}`;
const suiteDirectory = join(root, '.local/evaluation', id);
await mkdir(suiteDirectory, {recursive: true});
// Freeze UI settings once for the suite; source checks and explicit --config runs stay independent.
const settingsSnapshot = join(suiteDirectory, 'model-settings.json');
const savedSecrets = [];
if (!sourcesOnly && !args.some(arg => arg.startsWith('--config='))) {
  try {
    const settingsText = await readFile(join(root, '.local/model-settings.json'), 'utf8');
    const settings = JSON.parse(settingsText);
    for (const role of Object.values(settings.roles || {})) if (role['api-key']) savedSecrets.push(role['api-key']);
    await writeFile(settingsSnapshot, settingsText, {mode: 0o600});
  } catch (error) { if (error.code !== 'ENOENT') throw new Error('Saved model settings could not be read.'); }
}

// The Java registry is the only authority for provider defaults, credentials and capabilities.
{
  const abort = new AbortController();
  const interrupt = () => abort.abort(new Error('Evaluation interrupted.'));
  process.on('SIGINT', interrupt); process.on('SIGTERM', interrupt);
  try {
    const java = env.JAVA_HOME ? join(env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java';
    const versionResult = await promisify(execFile)(java, ['-version'], {env, windowsHide: true, timeout: 10000});
    // JAVA_TOOL_OPTIONS 等提示可能先于版本输出，不能把 stderr 第一行当作 Java 版本。
    const javaVersion = [versionResult.stderr, versionResult.stdout].join('\n').split(/\r?\n/)
      .find(line => /^(?:openjdk|java) version "\d+/.test(line)) || '';
    const major = Number(/version "(\d+)/.exec(javaVersion)?.[1]);
    if (!(major >= 21)) throw new Error('JDK 21 or newer is required. Set JAVA_HOME before running the evaluator.');
    const jar = join(root, 'target/supportops-0.1.0-SNAPSHOT.jar');
    const jarSha256 = createHash('sha256').update(await readFile(jar)).digest('hex');
    for (const hybrid of compare ? [false, true] : [args.includes('--hybrid')]) {
      abort.signal.throwIfAborted();
      const directory = join(suiteDirectory, sourcesOnly ? 'sources' : hybrid ? 'hybrid' : 'lexical');
      await mkdir(directory, {recursive: true});
      const runtimeJar = join(directory, 'application.jar');
      await copyFile(jar, runtimeJar);
      const groupSettings = join(directory, 'model-settings.json');
      try {
        const settings = JSON.parse(await readFile(settingsSnapshot, 'utf8'));
        if (!hybrid && settings.roles) delete settings.roles.embedding;
        await writeFile(groupSettings, JSON.stringify(settings), {mode: 0o600});
      } catch (error) { if (error.code !== 'ENOENT') throw new Error('Evaluation settings snapshot could not be prepared.'); }
      const workspaceKey = randomUUID().replaceAll('-', '').slice(0, 24);
      const workspaceCommand = operation => promisify(execFile)(java, [
        '-Dloader.main=io.supportops.migration.IsolatedKnowledgeWorkspace', '-cp', runtimeJar,
        'org.springframework.boot.loader.launch.PropertiesLauncher', operation, workspaceKey
      ], {env, cwd: root, windowsHide: true, timeout: 60000, maxBuffer: 1024 * 1024});
      let workspace;
      try {
        const prepared = await workspaceCommand('prepare');
        workspace = JSON.parse(prepared.stdout.split(/\r?\n/).find(line => line.startsWith('WORKSPACE:')).slice(10));
      } catch { throw new Error('Isolated MySQL preparation failed. Start the test infrastructure described in docs/knowledge.md.'); }
      const childEnv = {...env};
      Object.assign(childEnv, {
        SUPPORTOPS_DB_URL: workspace.mysql + workspace.database + '?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC',
        SUPPORTOPS_DB_USER: 'supportops', SUPPORTOPS_DB_PASSWORD: 'synthetic-knowledge-app',
        SUPPORTOPS_EXCEL_SCHEMA: workspace.excel, SUPPORTOPS_EXCEL_QUERY_USER: 'supportops_reader', SUPPORTOPS_EXCEL_QUERY_PASSWORD: 'synthetic-knowledge-reader',
        SUPPORTOPS_MINIO_ENDPOINT: env.SUPPORTOPS_TEST_MINIO || 'http://127.0.0.1:29000',
        SUPPORTOPS_MINIO_ACCESS_KEY: 'synthetic-local', SUPPORTOPS_MINIO_SECRET_KEY: 'synthetic-knowledge-minio', SUPPORTOPS_MINIO_BUCKET: workspace.prefix,
        SUPPORTOPS_ES_ENDPOINT: env.SUPPORTOPS_TEST_ES || 'http://127.0.0.1:29200',
        SUPPORTOPS_ES_USER: '', SUPPORTOPS_ES_PASSWORD: '', SUPPORTOPS_ES_PREFIX: workspace.prefix
      });
      if (sourcesOnly) for (const key of Object.keys(childEnv).filter(key => key.endsWith('_API_KEY'))) childEnv[key] = '';
      const secrets = [...savedSecrets, ...configurationSecrets(childEnv)].sort((a, b) => b.length - a.length);
      const redact = text => secrets.reduce((value, secret) => value.replaceAll(secret, '[REDACTED]'), text);
      const log = createWriteStream(join(directory, 'application.log'));
      const child = spawn(java, [
        '-Dpdfbox.fontcache=.cache/pdfbox', '-jar', runtimeJar,
        '--server.address=127.0.0.1', '--server.port=0', '--spring.config.location=classpath:application.yml',
        '--supportops.knowledge.jobs.scan-ms=1000',
        `--supportops.settings.file=${groupSettings}`,
        ...(!hybrid ? ['--supportops.embedding.provider=disabled'] : []),
        '--supportops.model.max-steps=10', '--supportops.model.timeout-seconds=120',
      ], {cwd: root, env: childEnv, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe']});
      const lifecycle = {pid: child.pid, startedAt: new Date().toISOString(), javaVersion, jarSha256, workspace};
      let closed = false, startupError, port;
      const closedPromise = new Promise(resolveClosed => {
        child.on('error', () => { startupError = new Error('Java process could not be started.'); });
        child.on('close', (code, signal) => { closed = true; lifecycle.exitCode = code; lifecycle.signal = signal; resolveClosed(); });
      });
      // Decode and redact complete lines, including keys split across stream chunks.
      for (const stream of [child.stdout, child.stderr]) {
        stream.setEncoding('utf8');
        let pending = '';
        stream.on('data', chunk => {
          pending += chunk;
          let end;
          while ((end = pending.indexOf('\n')) >= 0) {
            const line = pending.slice(0, end + 1); pending = pending.slice(end + 1);
            log.write(redact(line));
            const match = /Tomcat started on port (\d+)/.exec(line);
            if (match && stream === child.stdout) port = Number(match[1]);
          }
        });
        stream.on('end', () => { if (pending) log.write(redact(pending)); });
      }
      try {
        const deadline = Date.now() + 60000;
        while (!port) {
          abort.signal.throwIfAborted();
          if (closed || startupError) throw startupError ?? new Error('Evaluation app exited before becoming ready. See its local application.log.');
          if (Date.now() > deadline) throw new Error('Evaluation app startup timed out.');
          await new Promise(r => setTimeout(r, 200));
        }
        lifecycle.port = port;
        await writeFile(join(directory, 'runtime.json'), JSON.stringify(lifecycle, null, 2));
        // 仅为本次随机数据库创建一次性账号，凭据与 Cookie 不写入评测报告。
        const authentication = await fetch(`http://127.0.0.1:${port}/api/auth/setup`, {
          method: 'POST', headers: {'Content-Type': 'application/json', 'X-SupportOps-Request': '1'},
          body: JSON.stringify({username: 'evaluation_admin', password: randomUUID() + randomUUID()}),
          signal: AbortSignal.timeout(45000)
        });
        if (!authentication.ok) throw new Error('Isolated evaluation account setup failed.');
        const cookie = authentication.headers.get('set-cookie')?.split(';')[0];
        if (!cookie) throw new Error('Isolated evaluation login session missing.');
        const result = await evaluate({cookie, base: `http://127.0.0.1:${port}`, directory, sourcesOnly, hybrid, signal: abort.signal, metadata: {javaVersion, jarSha256, maxSteps: 10, timeoutSeconds: 120}});
        console.log(`${result.report.status}: ${result.reportPath}`);
        process.exitCode = Math.max(process.exitCode ?? 0, result.exitCode);
        if (result.exitCode) break;
      } finally {
        // Only the child created above is stopped. No port-based or process-name based termination.
        if (!closed) {
          child.kill('SIGTERM');
          let timer;
          await Promise.race([closedPromise, new Promise(r => { timer = setTimeout(r, 10000); })]);
          clearTimeout(timer);
          if (!closed) { child.kill('SIGKILL'); await closedPromise; }
        }
        lifecycle.stoppedAt = new Date().toISOString();
        await new Promise(resolveLog => log.end(resolveLog));
        await writeFile(join(directory, 'runtime.json'), JSON.stringify(lifecycle, null, 2));
        try { await workspaceCommand('cleanup'); }
        catch { throw new Error('Evaluation resource cleanup failed; generated resource names are recorded in runtime.json.'); }
      }
    }
  } catch (error) {
    const reason = error.code === 'ENOENT' ? 'JDK or packaged JAR not found. Set JAVA_HOME and run Maven verify first.' : error.message;
    await writeFile(join(suiteDirectory, 'launcher-error.json'), JSON.stringify({status: 'ERROR', reason}, null, 2));
    console.error(reason); process.exitCode = 1;
  } finally { process.off('SIGINT', interrupt); process.off('SIGTERM', interrupt); }
}
