import {spawn} from 'node:child_process';
import {join, resolve, dirname} from 'node:path';
import {fileURLToPath} from 'node:url';
import {loadLocalConfig, configurationSecrets} from './local-config.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
if (process.argv.includes('--help')) {
  console.log('Set SUPPORTOPS_MIGRATION_H2_URL, SUPPORTOPS_DB_URL/USER/PASSWORD, then run node scripts/migrate-h2.mjs. Stop both applications and back up the H2 file first. The MySQL target must be empty.');
  process.exit(0);
}
if (process.argv.length !== 2) throw new Error('Use --help for the offline migration procedure.');
const env = await loadLocalConfig(join(root, '.local/model.env'));
if (!env.SUPPORTOPS_MIGRATION_H2_URL?.startsWith('jdbc:h2:file:') || !env.SUPPORTOPS_DB_URL?.startsWith('jdbc:mysql:')) throw new Error('Explicit source H2 and target MySQL URLs are required.');
// Only the migration CLI reads JDBC directly; the application still uses MyBatis-Plus.
const java = env.JAVA_HOME ? join(env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java';
const child = spawn(java, ['-Dloader.main=io.supportops.migration.H2ToMysqlMigration', '-cp', join(root, 'target/supportops-0.1.0-SNAPSHOT.jar'),
  'org.springframework.boot.loader.launch.PropertiesLauncher'], {cwd: root, env, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe']});
const secrets = configurationSecrets(env);
for (const [stream, destination] of [[child.stdout, process.stdout], [child.stderr, process.stderr]]) {
  stream.setEncoding('utf8'); let pending = '';
  const emit = value => destination.write(secrets.reduce((text, secret) => text.replaceAll(secret, '[REDACTED]'), value));
  stream.on('data', data => { pending += data; let end; while ((end = pending.indexOf('\n')) >= 0) { emit(pending.slice(0, end + 1)); pending = pending.slice(end + 1); } });
  stream.on('end', () => { if (pending) emit(pending); });
}
child.on('error', () => { console.error('Migration process could not start. Check JDK 21 and the packaged JAR.'); process.exitCode = 1; });
child.on('close', code => { process.exitCode = code ?? 1; });
