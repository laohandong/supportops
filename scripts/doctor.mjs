import {resolve, dirname} from 'node:path';
import {fileURLToPath, pathToFileURL} from 'node:url';
import {readFile} from 'node:fs/promises';
import {loadLocalConfig} from './local-config.mjs';
import {options, check, execute, javaExecutable, javaProbe, digest, exitFor, printResult} from './reliability/common.mjs';
import {availablePort} from './reliability/runtime.mjs';
import {configurationPort, explicitPort} from './reliability/port.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
export async function doctor(args = process.argv.slice(2)) {
  const checks = [];
  try {
    const input = options(args, ['config', 'jar', 'workdir', 'settings', 'providers', 'port', 'deployment'], ['json']);
    const env = await loadLocalConfig(resolve(root, input.config || '.local/model.env'));
    if (input.config) await readFile(resolve(root, input.config));
    const java = javaExecutable(env);
    let validJava = false, validJar = false;
    checks.push(check('node', Number(process.versions.node.split('.')[0]) >= 22 ? 'PASS' : 'FAIL', 'NODE_22_REQUIRED'));
    try {
      const version = await execute(java, ['-version'], {env, timeout: 10000, windowsHide: true});
      validJava = Number(/version "(\d+)/.exec(version.stderr + version.stdout)?.[1]) === 21;
      checks.push(check('jdk', validJava ? 'PASS' : 'FAIL', validJava ? 'JDK_21' : 'JDK_21_REQUIRED'));
    } catch { checks.push(check('jdk', 'FAIL', 'JDK_UNAVAILABLE')); }
    const jar = resolve(root, input.jar || 'target/supportops-0.1.0-SNAPSHOT.jar');
    let jarSha256;
    try { jarSha256 = await digest(jar); validJar = true; checks.push(check('jar', 'PASS', 'JAR_HASHED')); }
    catch { checks.push(check('jar', 'FAIL', 'JAR_UNAVAILABLE')); }
    const properties = [
      ...(input.settings ? [`-Dsupportops.settings.file=${resolve(root, input.settings)}`] : []),
      ...(input.providers !== undefined ? [`-Dspring.config.import=${input.providers === 'none' ? '' : 'optional:file:' + resolve(root, input.providers).replaceAll('\\', '/')}`] : [])];
    let port = explicitPort(input.port);
    if (port === undefined && validJava && validJar) {
      port = await configurationPort({java, jar, env, cwd: resolve(root, input.workdir || '.'), properties});
    }
    if (port === undefined) {
      checks.push(check('port', 'UNKNOWN', 'PORT_CONFIGURATION_UNVERIFIED'));
    } else {
      const free = !port || await availablePort(port);
      checks.push(check('port', free ? 'PASS' : 'FAIL', !port ? 'AUTOMATIC_PORT_EXPLICIT' : free ? 'PORT_AVAILABLE' : 'PORT_IN_USE'));
    }
    if (validJava && validJar) {
      try { checks.push(...await javaProbe(java, jar, 'io.supportops.ops.ReadOnlyDoctor', [], {env, cwd: resolve(root, input.workdir || '.'), properties, timeout: 35000})); }
      catch { checks.push(check('dependencies', 'UNKNOWN', 'PROBE_TIMEOUT_OR_UNREADABLE', 'EXPLICIT_CONFIGURATION')); }
    } else checks.push(check('dependencies', 'UNKNOWN', 'PROBE_NOT_RUN', 'EXPLICIT_CONFIGURATION'));
    if (input.deployment) {
      try {
        const deployment = JSON.parse(await readFile(resolve(root, input.deployment), 'utf8'));
        const result = await execute('docker', [...(deployment.dockerContext ? ['--context', deployment.dockerContext] : []), 'version', '--format', '{{.Server.Version}}'], {windowsHide: true, timeout: 8000});
        checks.push(check('docker', result.stdout.trim() ? 'PASS' : 'UNKNOWN', 'DECLARED_COMPOSE_ENGINE', 'DECLARED_DEPLOYMENT'));
      } catch { checks.push(check('docker', 'FAIL', 'DECLARED_DOCKER_UNAVAILABLE', 'DECLARED_DEPLOYMENT')); }
    } else checks.push(check('docker', 'NOT_APPLICABLE', 'EXTERNAL_DEPENDENCIES_NO_DOCKER_REQUIREMENT'));
    return {schemaVersion: 1, jarSha256, checks, exitCode: exitFor(checks)};
  } catch { return {schemaVersion: 1, checks: [...checks, check('input', 'FAIL', 'INPUT_INVALID')], exitCode: 2}; }
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const result = await doctor(); printResult(result, process.argv.includes('--json')); process.exitCode = result.exitCode;
}
