import {readFile, writeFile, access} from 'node:fs/promises';
import {join, resolve, dirname} from 'node:path';
import {createServer} from 'node:net';
import {randomUUID} from 'node:crypto';
import {atomicJson, execute, privateDirectory, digest} from './common.mjs';
import {systemEnvironment} from '../lib/runtime-safety.mjs';

const services = ['mysql', 'minio', 'elasticsearch'];
export function dockerArguments(deployment, args) { return [...(deployment.dockerContext ? ['--context', deployment.dockerContext] : []), ...args]; }
export async function docker(deployment, args, extra = {}) {
  return execute('docker', dockerArguments(deployment, args), {windowsHide: true, timeout: 15000, maxBuffer: 8 * 1024 * 1024, ...extra});
}
export function composeEnvironment(deployment) {
  return {...systemEnvironment(), HOMEDRIVE: process.env.HOMEDRIVE, HOMEPATH: process.env.HOMEPATH,
    ProgramData: process.env.ProgramData, ProgramFiles: process.env.ProgramFiles,
    SUPPORTOPS_DEPLOYMENT_ID: deployment.deploymentId,
    MYSQL_PORT: String(deployment.ports.mysql), MINIO_PORT: String(deployment.ports.minio), ES_PORT: String(deployment.ports.elasticsearch)};
}
export function composeArguments(deployment, args) {
  return ['compose', '-p', deployment.project, '-f', deployment.compose, '-f', deployment.snapshotCompose,
    ...(deployment.networkCompose ? ['-f', deployment.networkCompose] : []), ...args];
}

/** 仅显式指定的测试地址池启用；核对所有现有网络后分配新 /24，不移除或复用旧网络。 */
async function isolatedNetwork(deployment, pool) {
  if (!/^10\.(?:[0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])$/.test(pool)) throw new Error('TEST_NETWORK_POOL_INVALID');
  const ids = (await docker(deployment, ['network', 'ls', '-q'])).stdout.trim().split(/\s+/).filter(Boolean);
  const networks = ids.length ? JSON.parse((await docker(deployment, ['network', 'inspect', ...ids])).stdout) : [];
  const number = ip => ip.split('.').reduce((value, part) => value * 256 + Number(part), 0);
  const occupied = networks.flatMap(item => item.IPAM?.Config || []).map(item => item.Subnet).filter(value => /^\d+\./.test(value)).map(value => {
    const [ip, bits] = value.split('/'); const size = 2 ** (32 - Number(bits)); const start = Math.floor(number(ip) / size) * size;
    return [start, start + size - 1];
  });
  for (let part = 200; part < 255; part++) {
    const subnet = pool + '.' + part + '.0/24', start = number(pool + '.' + part + '.0');
    if (occupied.some(([low, high]) => start <= high && start + 255 >= low)) continue;
    deployment.networkCompose = join(deployment.directory, 'isolated-network.compose.json');
    await writeFile(deployment.networkCompose, JSON.stringify({networks:{default:{ipam:{config:[{subnet}]}}}}), {flag:'wx',mode:0o600});
    return;
  }
  throw new Error('TEST_NETWORK_POOL_EXHAUSTED');
}
async function port() {
  return new Promise((done, reject) => {
    const server = createServer(); server.once('error', reject);
    server.listen(0, '127.0.0.1', () => { const value = server.address().port; server.close(() => done(value)); });
  });
}

/** 先登记创建意图；服务、卷或目录名称碰撞立即拒绝。 */
export async function createDeployment(root, directory, {ports, deploymentId = randomUUID(), dockerContext = process.platform === 'win32' ? 'desktop-linux' : undefined, composeRoot = process.env.SUPPORTOPS_RD_COMPOSE_ROOT || root} = {}) {
  if (!/^[0-9a-f-]{36}$/.test(deploymentId)) throw new Error('DEPLOYMENT_ID_INVALID');
  const project = 'so-rd-' + deploymentId.replaceAll('-', '').slice(0, 16);
  const deployment = {schemaVersion: 1, kind: 'DEDICATED_COMPOSE_V1', deploymentId, project, directory: resolve(directory),
    dockerContext, compose: join(composeRoot, 'infrastructure/compose.yml'), snapshotCompose: join(composeRoot, 'infrastructure/reliability-snapshots.compose.yml'),
    ports: ports || {mysql: await port(), minio: await port(), elasticsearch: await port()}, state: 'PLANNED',
    database: 'supportops', excel: 'supportops_excel', bucket: 'supportops', indexPrefix: 'supportops',
    config: join(directory, 'application.env'), settings: join(directory, 'model-settings.json'), providers: join(directory, 'providers.yml'),
    runtimeRoot: join(directory, 'runtime'), processes: [], resources: {}, createdAt: new Date().toISOString()};
  await privateDirectory(directory);
  const descriptor = join(directory, 'deployment.json');
  await atomicJson(descriptor, deployment);
  const existingContainers = (await docker(deployment, ['ps', '-aq', '--filter', `label=com.docker.compose.project=${project}`])).stdout.trim();
  const existingVolumes = (await docker(deployment, ['volume', 'ls', '-q', '--filter', `label=com.docker.compose.project=${project}`])).stdout.trim();
  if (existingContainers || existingVolumes) throw new Error('DEPLOYMENT_RESOURCE_COLLISION');
  deployment.daemonId = (await docker(deployment, ['info', '--format', '{{.ID}}'])).stdout.trim();
  if (process.env.SUPPORTOPS_RD_SUBNET_POOL) await isolatedNetwork(deployment, process.env.SUPPORTOPS_RD_SUBNET_POOL);
  const env = [
    `SUPPORTOPS_DB_URL=jdbc:mysql://127.0.0.1:${deployment.ports.mysql}/supportops?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC`,
    'SUPPORTOPS_DB_USER=supportops', 'SUPPORTOPS_DB_PASSWORD=synthetic-knowledge-app',
    'SUPPORTOPS_EXCEL_SCHEMA=supportops_excel', 'SUPPORTOPS_EXCEL_QUERY_USER=supportops_reader', 'SUPPORTOPS_EXCEL_QUERY_PASSWORD=synthetic-knowledge-reader',
    `SUPPORTOPS_MINIO_ENDPOINT=http://127.0.0.1:${deployment.ports.minio}`, 'SUPPORTOPS_MINIO_ACCESS_KEY=synthetic-local',
    'SUPPORTOPS_MINIO_SECRET_KEY=synthetic-knowledge-minio', 'SUPPORTOPS_MINIO_BUCKET=supportops',
    `SUPPORTOPS_ES_ENDPOINT=http://127.0.0.1:${deployment.ports.elasticsearch}`, 'SUPPORTOPS_ES_PREFIX=supportops',
    'SUPPORTOPS_EMBEDDING_PROVIDER=disabled', 'SUPPORTOPS_RERANK_ENABLED=false', 'SUPPORTOPS_FEISHU_ENABLED=false', 'SUPPORTOPS_EVALUATION_ENABLED=false'];
  await writeFile(deployment.config, env.join('\n') + '\n', {mode: 0o600, flag: 'wx'});
  await atomicJson(deployment.settings, {format: 1, roles: {}});
  await writeFile(deployment.providers, 'supportops:\n  providers: {}\n', {mode: 0o600, flag: 'wx'});
  deployment.state = 'CREATING'; await atomicJson(descriptor, deployment);
  try {
    // 冷环境的固定源码构建可超过服务健康等待时间，先完成构建再进入180秒就绪检查。
    await docker(deployment, composeArguments(deployment, ['build', 'minio']), {env: composeEnvironment(deployment), timeout: 1800000});
    await docker(deployment, composeArguments(deployment, ['up', '-d', '--wait', '--wait-timeout', '180']), {env: composeEnvironment(deployment), timeout: 200000});
    for (const service of services) {
      const name = `${project}-${service}-1`;
      const details = JSON.parse((await docker(deployment, ['inspect', name])).stdout)[0];
      if (details.Config.Labels['io.supportops.deployment'] !== deploymentId) throw new Error('DEPLOYMENT_OWNERSHIP_MISMATCH');
      deployment.resources[service] = {name, id: details.Id, image: details.Image,
        imageName: details.Config.Image, volumes: details.Mounts.filter(m => m.Type === 'volume').map(m => m.Name).sort()};
    }
    // 快照挂载是本轮新建的专属卷；只为该挂载根授予 ES 服务身份写入权限。
    await docker(deployment, ['exec', '-u', '0', deployment.resources.elasticsearch.id, 'chown', '1000:0', '/snapshots']);
    deployment.state = 'READY'; await atomicJson(descriptor, deployment);
  } catch { deployment.state = 'PARTIAL'; await atomicJson(descriptor, deployment); throw new Error('DEPLOYMENT_CREATE_FAILED'); }
  return deployment;
}

/** 清单、daemon、容器和卷都必须匹配，不凭同名资源推断归属。 */
export async function verifyDeployment(deployment) {
  if (deployment.schemaVersion !== 1 || deployment.kind !== 'DEDICATED_COMPOSE_V1' || !/^[0-9a-f-]{36}$/.test(deployment.deploymentId)
      || !/^so-rd-[a-f0-9]{16}$/.test(deployment.project) || deployment.state !== 'READY') throw new Error('DEPLOYMENT_UNSUPPORTED');
  if ((await docker(deployment, ['info', '--format', '{{.ID}}'])).stdout.trim() !== deployment.daemonId) throw new Error('DEPLOYMENT_DAEMON_MISMATCH');
  for (const service of services) {
    const resource = deployment.resources[service];
    if (!resource) throw new Error('DEPLOYMENT_RESOURCE_INCOMPLETE');
    const details = JSON.parse((await docker(deployment, ['inspect', resource.id])).stdout)[0];
    if (details.Id !== resource.id || details.Config.Labels['io.supportops.deployment'] !== deployment.deploymentId
        || details.Image !== resource.image || JSON.stringify(details.Mounts.filter(m => m.Type === 'volume').map(m => m.Name).sort()) !== JSON.stringify([...resource.volumes].sort())) throw new Error('DEPLOYMENT_OWNERSHIP_MISMATCH');
    const port = service === 'mysql' ? '3306/tcp' : service === 'minio' ? '9000/tcp' : '9200/tcp';
    const binding = details.HostConfig.PortBindings[port];
    if (binding?.length !== 1 || binding[0].HostIp !== '127.0.0.1' || Number(binding[0].HostPort) !== deployment.ports[service]) throw new Error('DEPLOYMENT_ENDPOINT_MISMATCH');
    for (const volume of resource.volumes) {
      const item = JSON.parse((await docker(deployment, ['volume', 'inspect', volume])).stdout)[0];
      if (item.Labels['io.supportops.deployment'] !== deployment.deploymentId) throw new Error('DEPLOYMENT_VOLUME_MISMATCH');
    }
  }
}

export async function registerRuntime(deployment, runtime) {
  const path = join(deployment.directory, 'deployment.json');
  const current = JSON.parse(await readFile(path, 'utf8'));
  if (current.deploymentId !== deployment.deploymentId) throw new Error('DEPLOYMENT_OWNERSHIP_MISMATCH');
  if (!current.processes.some(item => item.runtimeId === runtime.record.runtimeId)) {
    current.processes.push({runtime: join(runtime.directory, 'runtime.json'), runtimeId: runtime.record.runtimeId});
  }
  current.jarSha256 = await digest(runtime.record.jar);
  current.jar = runtime.record.jar;
  await atomicJson(path, current);
  Object.assign(deployment, current);
}

/** 只停止/删除本轮完整登记且仍匹配的实例，不接受未知残留。 */
export async function cleanDeployment(deployment, {keepVolumes = false} = {}) {
  await verifyDeployment(deployment);
  // Compose 会按项目寻找资源；在任何 stop/down 前拒绝清单之外的同项目对象。
  const containerIds = (await docker(deployment, ['ps', '-aq', '--no-trunc', '--filter', `label=com.docker.compose.project=${deployment.project}`])).stdout.trim().split(/\r?\n/).filter(Boolean).sort();
  const expectedIds = Object.values(deployment.resources).map(resource => resource.id).sort();
  if (JSON.stringify(containerIds) !== JSON.stringify(expectedIds)) throw new Error('CLEANUP_UNREGISTERED_CONTAINERS');
  const volumeNames = (await docker(deployment, ['volume', 'ls', '-q', '--filter', `label=com.docker.compose.project=${deployment.project}`])).stdout.trim().split(/\r?\n/).filter(Boolean).sort();
  const expectedVolumes = [...new Set(Object.values(deployment.resources).flatMap(resource => resource.volumes))].sort();
  if (JSON.stringify(volumeNames) !== JSON.stringify(expectedVolumes)) throw new Error('CLEANUP_UNREGISTERED_VOLUMES');
  await docker(deployment, composeArguments(deployment, keepVolumes ? ['stop'] : ['down', '--volumes']), {env: composeEnvironment(deployment), timeout: 60000});
  deployment.state = keepVolumes ? 'STORAGE_STOPPED' : 'CLEANED';
  await atomicJson(join(deployment.directory, 'deployment.json'), deployment);
}
