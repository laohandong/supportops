import {spawn} from 'node:child_process';
import {createReadStream, createWriteStream} from 'node:fs';
import {pipeline} from 'node:stream/promises';
import {readFile, writeFile, mkdir, copyFile, stat, unlink, access, chmod} from 'node:fs/promises';
import {join, resolve, relative, dirname} from 'node:path';
import {randomUUID} from 'node:crypto';
import {isDeepStrictEqual} from 'node:util';
import {atomicJson, digest, hash, files, safeFile, privateDirectory, javaExecutable, javaProbe, verifyPrivateDirectory, assertNoLinkAncestors} from './common.mjs';
import {verifyDeployment, docker, dockerArguments, createDeployment} from './deployment.mjs';
import {inspect} from './runtime.mjs';
import {connection, closeDatabase, query, identifier, databaseSnapshot, minio, objectList, objectBytes, es, elasticSnapshot} from './storage.mjs';
import {maintenanceLease,restoreArguments} from './maintenance.mjs';
import {externalDependencies,validateExternalDependencies} from './external-dependencies-v1.mjs';

const LIMIT = 2 * 1024 * 1024 * 1024;
const systemSchemas = ['information_schema', 'mysql', 'performance_schema', 'sys'];

/** 仅已登记运行、旧处理器与评测子进程均确认退出才可采集。 */
export async function stoppedWriters(deployment, env = process.env) {
  if (!deployment.processes?.length || !deployment.jar || !deployment.jarSha256) throw new Error('WRITER_OWNERSHIP_UNKNOWN');
  const proof = [];
  for (const item of deployment.processes) {
    const record = JSON.parse(await readFile(item.runtime, 'utf8'));
    if (record.runtimeId !== item.runtimeId) throw new Error('WRITER_OWNERSHIP_UNKNOWN');
    const state = await inspect(record, {env});
    if (!['STOPPED', 'FAILED'].includes(state.phase) || !['PROCESS_EXITED', 'PID_REUSED'].includes(state.checks[0].code)) throw new Error('APPLICATION_NOT_CONFIRMED_STOPPED');
    proof.push({runtimeId: record.runtimeId, pid: record.pid, processStartedAt: record.processStartedAt, code: state.checks[0].code});
  }
  const database = await connection(deployment);
  try {
    const schemas = (await query(database, 'SHOW DATABASES')).map(row => Object.values(row)[0]).filter(name => !systemSchemas.includes(name)).sort();
    if (!isDeepStrictEqual(schemas, [deployment.database, deployment.excel].sort())) throw new Error('UNKNOWN_DATABASE_RESOURCES');
    const count = async (table, condition) => Number((await query(database, `SELECT COUNT(*) AS total FROM ${identifier(deployment.database)}.${identifier(table)} WHERE ${condition}`))[0].total);
    if (await count('runs', "status IN ('QUEUED','RUNNING')") || await count('knowledge_tasks', "status IN ('PENDING','RUNNING','RETRY_WAIT')")) throw new Error('BACKGROUND_WRITER_ACTIVE');
    if (await count('evaluation_experiments', "status IN ('QUEUED','PREPARING','RUNNING','CANCELLING')")) throw new Error('EVALUATION_ACTIVE');
    if (await count('evaluation_runner_guard', 'attempt_id IS NOT NULL')) throw new Error('EVALUATION_GUARD_BUSY');
    if (await count('evaluation_attempts', "recovery_status IN ('PENDING','IMPORTING','FAILED') OR cleanup_status NOT IN ('CLEANED','NOT_CREATED')")) throw new Error('EVALUATION_RECOVERY_OR_CLEANUP_PENDING');
    const attempts = await query(database, `SELECT * FROM ${identifier(deployment.database)}.evaluation_attempts`);
    const executions = await query(database, `SELECT process_id AS pid,process_started_at AS started FROM ${identifier(deployment.database)}.remediation_executions WHERE status IN ('RUNNING','UNKNOWN')`);
    const identities = executions.map(row => ({pid: row.pid, started: row.started}));
    for (const attempt of attempts) {
      if (attempt.pid) identities.push({pid: attempt.pid, started: attempt.process_started_at});
      let processes;
      try { processes = JSON.parse(attempt.process_json); } catch { throw new Error('EVALUATION_PROCESS_EVIDENCE_INVALID'); }
      for (const item of Array.isArray(processes) ? processes : processes.processes || []) {
        if (item.pid) identities.push({pid: item.pid, started: item.processStartedAt || item.startedAt});
      }
    }
    for (const identity of identities) {
      const current = await javaProbe(javaExecutable(env), deployment.jar, 'io.supportops.ops.ProcessProbe', [String(identity.pid)], {env, timeout: 15000});
      if (current.state !== 'EXITED' && !(current.state === 'ALIVE' && identity.started && current.processStartedAt && identity.started !== current.processStartedAt)) throw new Error('EXECUTION_IDENTITY_UNKNOWN_OR_ACTIVE');
    }
    if (await count('feishu_inbox', "state IN ('RECEIVED','WAITING','PENDING','PROCESSING')")
        || await count('feishu_outbox', "state IN ('PENDING','SENDING','RETRY_WAIT')")) throw new Error('FEISHU_DELIVERY_PENDING');
    const processList = await query(database, 'SHOW PROCESSLIST');
    if (processList.some(row => String(row.Id) !== String(database.threadId) && row.User !== 'event_scheduler' && row.User !== 'system user'
        && !/^SELECT\s+1\s*$/i.test(row.Info || ''))) throw new Error('UNKNOWN_DATABASE_WRITER');
    return {observedAt: new Date().toISOString(), applications: proof, attempts};
  } finally { await closeDatabase(database); }
}

/** 备份默认仅产生只读计划，实际操作必须再次指定该计划及部署身份。 */
export async function backupPlan(deployment, planPath) {
  await verifyDeployment(deployment);
  const proof = await stoppedWriters(deployment);
  const plan = {schemaVersion: 1, kind: 'STOPPED_BACKUP_V1', planId: randomUUID(), deploymentId: deployment.deploymentId,
    deploymentPath: join(deployment.directory, 'deployment.json'), deploymentSha256: await digest(join(deployment.directory, 'deployment.json')),
    jarSha256: deployment.jarSha256, proof, createdAt: new Date().toISOString()};
  await atomicJson(planPath, plan);
  return plan;
}

/** 独占锁不按超时清除；未知导出仍在途时保留锁及失败清单。 */
async function lock(deployment, backupId) {
  return maintenanceLease(deployment,deployment.jar,'BACKUP',backupId);
}

/** 官方 MySQL 工具经管道导出/导入；密码只从已登记容器环境读取，不进 argv。 */
export async function logicalSql(deployment, operation, path) {
  const databaseNames = [deployment.database, deployment.excel];
  databaseNames.forEach(identifier);
  const command = operation === 'dump'
    ? `MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --routines --triggers --events --hex-blob --set-gtid-purged=OFF --no-tablespaces --databases ${databaseNames.join(' ')}`
    : 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --binary-mode';
  const child = spawn('docker', dockerArguments(deployment, ['exec', ...(operation === 'dump' ? [] : ['-i']), deployment.resources.mysql.id, 'sh', '-c', command]), {windowsHide: true, stdio: ['pipe', 'pipe', 'pipe']});
  let failed = false, bytes = 0;
  child.stderr.on('data', () => {});
  child.stdout.on('data', data => { bytes += data.length; if (bytes > LIMIT) { failed = true; child.kill(); } });
  const completion = new Promise((done, reject) => { child.once('error', () => reject(new Error('MYSQL_TOOL_FAILED'))); child.once('close', code => code === 0 && !failed ? done() : reject(new Error('MYSQL_TOOL_FAILED'))); });
  completion.catch(() => {});
  const timer = setTimeout(() => { failed = true; child.kill(); }, 120000);
  try {
    if (operation === 'dump') { child.stdin.end(); await pipeline(child.stdout, createWriteStream(path, {flags: 'wx', mode: 0o600})); }
    else { await pipeline(createReadStream(path), child.stdin); child.stdout.resume(); }
    await completion;
  } catch {
    // 管道或写盘失败也要结束本次工具；无法证明退出时由调用方保留维护锁。
    child.kill();
    await Promise.race([completion.catch(() => {}), new Promise(done => { const wait = setTimeout(done, 10000); wait.unref(); })]);
    throw new Error('MYSQL_TRANSFER_INTERRUPTED');
  }
  finally { clearTimeout(timer); }
}

export async function captureObjects(deployment, directory) {
  const client = minio(deployment);
  const buckets = (await client.listBuckets()).map(item => item.name);
  if (buckets.some(bucket => bucket !== deployment.bucket)) throw new Error('UNKNOWN_MINIO_RESOURCES');
  if (!buckets.length) {
    if (directory) await mkdir(join(directory, 'objects'));
    return {bucket: deployment.bucket, exists: false, objects: []};
  }
  const versioning = await client.getBucketVersioning(deployment.bucket);
  if (versioning?.Status || versioning?.MFADelete === 'Enabled') throw new Error('MINIO_VERSIONING_UNSUPPORTED');
  try { const retention = await client.getObjectLockConfig(deployment.bucket); if (retention?.objectLockEnabled || retention?.ObjectLockEnabled) throw new Error('MINIO_RETENTION_UNSUPPORTED'); }
  catch (error) { if (!['ObjectLockConfigurationNotFoundError', 'NoSuchObjectLockConfiguration'].includes(error.code) && error.message !== 'MINIO_RETENTION_UNSUPPORTED') throw new Error('MINIO_RETENTION_UNKNOWN'); if (error.message === 'MINIO_RETENTION_UNSUPPORTED') throw error; }
  const objects = [];
  if (directory) await mkdir(join(directory, 'objects'));
  for (const item of await objectList(client, deployment.bucket)) {
    const bytes = await objectBytes(client, deployment.bucket, item.name);
    const metadata = await client.statObject(deployment.bucket, item.name);
    const file = 'objects/' + hash(item.name) + '.bin';
    if (directory) await writeFile(join(directory, file), bytes, {mode: 0o600, flag: 'wx'});
    const tagSet = await client.getObjectTagging(deployment.bucket, item.name);
    const tags = Object.fromEntries(tagSet.map(tag => [tag.Key, String(tag.Value)]).sort((left,right) => left[0].localeCompare(right[0])));
    objects.push({key: item.name, file, size: bytes.length, sha256: hash(bytes), metadata: metadata.metaData, tags});
  }
  return {bucket: deployment.bucket, exists: true, objects};
}

/** 只有 SQL、原件、官方完整快照与本地状态全都核实后才原子标记 COMPLETE。 */
export async function makeBackup(planPath, deploymentId, output, {fault = async () => {}} = {}) {
  const plan = JSON.parse(await readFile(planPath, 'utf8'));
  if (plan.schemaVersion !== 1 || plan.kind !== 'STOPPED_BACKUP_V1' || plan.deploymentId !== deploymentId) throw new Error('BACKUP_PLAN_INVALID');
  if (await digest(plan.deploymentPath) !== plan.deploymentSha256) throw new Error('BACKUP_PLAN_CHANGED');
  const deployment = JSON.parse(await readFile(plan.deploymentPath, 'utf8'));
  await verifyDeployment(deployment); await stoppedWriters(deployment);
  const backupId = randomUUID();
  const release = await lock(deployment, backupId);
  let manifest;
  try {
    await privateDirectory(output);
    manifest = {schemaVersion: 1, kind: 'STOPPED_COMPOSE_BACKUP_V1', backupId, status: 'WRITING', deploymentId,
      capturedAt: new Date().toISOString(), source: deployment, jarSha256: deployment.jarSha256,
      stopProof: await stoppedWriters(deployment), files: {}, materials: [], permissions: process.platform === 'win32' ? 'CURRENT_SID_ONLY' : '0700/0600'};
    await atomicJson(join(output, 'manifest.json'), manifest);
    const before = await databaseSnapshot(deployment);
    await logicalSql(deployment, 'dump', join(output, 'databases.sql'));
    await fault('SQL_EXPORTED');
    manifest.mysql = before;
    manifest.minio = await captureObjects(deployment, output);
    manifest.elastic = await elasticSnapshot(deployment);
    const repository = 'rd-' + backupId, location = '/snapshots/' + backupId;
    const indices = Object.keys(manifest.elastic.indices);
    manifest.esRepository = {repository, location, snapshot: 'complete', indices};
    if (indices.length) {
      await es(deployment, 'PUT', `/_snapshot/${repository}`, {type: 'fs', settings: {location, compress: true}});
      const response = await es(deployment, 'PUT', `/_snapshot/${repository}/complete?wait_for_completion=true`, {indices: indices.join(','), include_global_state: false, partial: false}, 120000);
      if (response.snapshot?.state !== 'SUCCESS' || !isDeepStrictEqual([...response.snapshot.indices].sort(), indices.sort())) throw new Error('ES_SNAPSHOT_INCOMPLETE');
      await es(deployment, 'DELETE', `/_snapshot/${repository}`);
      await mkdir(join(output, 'es-repository'));
      await docker(deployment, ['cp', `${deployment.resources.elasticsearch.id}:${location}/.`, join(output, 'es-repository')], {timeout: 60000});
    } else { manifest.esRepository.empty = true; await mkdir(join(output, 'es-repository')); }
    await fault('SNAPSHOT_EXPORTED');
    await mkdir(join(output, 'configuration'));
    for (const [key, source] of Object.entries({deployment: plan.deploymentPath, environment: deployment.config, settings: deployment.settings, providers: deployment.providers})) {
      await copyFile(source, join(output, 'configuration', key + (key === 'environment' ? '.env' : key === 'providers' ? '.yml' : '.json')));
    }
    const dependencies=await externalDependencies(deployment);
    await atomicJson(join(output,'configuration/external-dependencies.json'),dependencies);
    await copyFile(deployment.jar, join(output, 'application.jar'));
    for (const attempt of manifest.stopProof.attempts) {
      const originalPath = resolve(attempt.workspace_path);
      let base = originalPath;
      if (relative(deployment.directory, base).startsWith('..') || !relative(deployment.directory, base)) {
        const archived = deployment.archivedMaterials?.find(item => item.attemptId === attempt.id && item.originalPath === originalPath);
        if (!archived) throw new Error('EVALUATION_MATERIAL_OUTSIDE_DEPLOYMENT');
        base = resolve(deployment.directory, archived.archive);
        if (relative(deployment.directory, base).startsWith('..')) throw new Error('EVALUATION_MATERIAL_OUTSIDE_DEPLOYMENT');
      }
      const destination = join(output, 'evaluation-materials', attempt.id); await mkdir(destination, {recursive: true});
      for (const key of await files(base)) {
        if (key.endsWith('.jar') || key.includes('/.cache/') || key.startsWith('.cache/')) continue;
        const source = await safeFile(base, key), target = join(destination, key);
        await mkdir(dirname(target), {recursive: true}); await copyFile(source, target);
      }
      manifest.materials.push({attemptId: attempt.id, originalPath, archive: 'evaluation-materials/' + attempt.id});
    }
    if (!isDeepStrictEqual(before, await databaseSnapshot(deployment))) throw new Error('SOURCE_CHANGED_DURING_BACKUP');
    if (!isDeepStrictEqual(manifest.elastic, await elasticSnapshot(deployment))) throw new Error('ES_CHANGED_DURING_BACKUP');
    if (!isDeepStrictEqual(manifest.minio, await captureObjects(deployment))) throw new Error('OBJECT_CHANGED_DURING_BACKUP');
    if (!isDeepStrictEqual(dependencies,await externalDependencies(deployment))) throw new Error('EXTERNAL_DEPENDENCIES_CHANGED_DURING_BACKUP');
    let bytes = 0;
    for (const key of await files(output)) {
      if (key === 'manifest.json') continue;
      const size = (await stat(join(output, key))).size; bytes += size;
      if (process.platform !== 'win32') await chmod(join(output, key), 0o600);
      if (bytes > LIMIT) throw new Error('BACKUP_SIZE_LIMIT');
      manifest.files[key] = {size, sha256: await digest(join(output, key))};
    }
    manifest.images = Object.fromEntries(Object.entries(deployment.resources).map(([name, resource]) => [name, {id: resource.image, name: resource.imageName}]));
    manifest.minioVersion = (await docker(deployment, ['exec', deployment.resources.minio.id, 'minio', '--version'])).stdout.split('\n')[0];
    manifest.status = 'COMPLETE'; manifest.completedAt = new Date().toISOString();
    await atomicJson(join(output, 'manifest.json'), manifest); await release(); return manifest;
  } catch (error) {
    if (manifest) { manifest.status = 'FAILED'; manifest.errorCode = /^[A-Z_]+$/.test(error.message) ? error.message : 'BACKUP_FAILED'; manifest.maintenanceLock = 'RETAINED'; await atomicJson(join(output, 'manifest.json'), manifest); }
    // 未证明所有外部导出都退出，保留锁；不得靠时间或异常类别猜测可释放。
    throw error;
  }
}

/** 任何目标写入前验证全部文件、集合、规模、版本和工具。 */
export async function validateBackup(directory) {
  await verifyPrivateDirectory(directory);
  const path = await safeFile(directory, 'manifest.json');
  const manifest = JSON.parse(await readFile(path, 'utf8'));
  if (manifest.schemaVersion !== 1 || manifest.kind !== 'STOPPED_COMPOSE_BACKUP_V1' || manifest.status !== 'COMPLETE'
      || !/^[0-9a-f-]{36}$/.test(manifest.backupId) || !manifest.files || !manifest.images || !manifest.esRepository) throw new Error('BACKUP_MANIFEST_UNSUPPORTED');
  if (!isDeepStrictEqual(Object.keys(manifest.mysql?.schemas ?? {}).sort(), ['supportops','supportops_excel'])
      || manifest.source.database !== 'supportops' || manifest.source.excel !== 'supportops_excel'
      || manifest.minio?.bucket !== 'supportops' || typeof manifest.minio.exists !== 'boolean'
      || !Array.isArray(manifest.minio.objects) || new Set(manifest.minio.objects.map(object => object.key)).size !== manifest.minio.objects.length) throw new Error('BACKUP_SCHEMA_INVALID');
  const expected = Object.keys(manifest.files).sort();
  const actual = (await files(directory)).filter(key => key !== 'manifest.json');
  if (!isDeepStrictEqual(expected, actual) || expected.length > 50000) throw new Error('BACKUP_FILE_SET_MISMATCH');
  let bytes = 0;
  for (const key of expected) {
    const file = await safeFile(directory, key), metadata = manifest.files[key];
    const size = (await stat(file)).size; bytes += size;
    if (!Number.isSafeInteger(metadata.size) || metadata.size !== size || bytes > LIMIT || await digest(file) !== metadata.sha256) throw new Error('BACKUP_CHECKSUM_INVALID');
  }
  if (!manifest.files['databases.sql'] || !manifest.files['application.jar'] || manifest.files['application.jar'].sha256 !== manifest.jarSha256
      || !manifest.files['configuration/environment.env'] || !manifest.files['configuration/settings.json'] || !manifest.files['configuration/providers.yml']) throw new Error('BACKUP_RESOURCES_INCOMPLETE');
  if(manifest.files['configuration/external-dependencies.json'])await validateExternalDependencies(await safeFile(directory,'configuration/external-dependencies.json'));
  const indices = Object.keys(manifest.elastic?.indices ?? {}).sort();
  if (manifest.esRepository.location !== '/snapshots/' + manifest.backupId || manifest.esRepository.repository !== 'rd-' + manifest.backupId
      || manifest.esRepository.snapshot !== 'complete' || !isDeepStrictEqual([...manifest.esRepository.indices].sort(), indices) || (indices.length === 0) !== (manifest.esRepository.empty === true)
      || indices.length && !expected.some(key => key.startsWith('es-repository/index-'))) throw new Error('BACKUP_SNAPSHOT_INVALID');
  for (const object of manifest.minio.objects) {
    if (typeof object.key !== 'string' || !object.key || object.file !== 'objects/' + hash(object.key) + '.bin'
        || !manifest.files[object.file] || manifest.files[object.file].sha256 !== object.sha256 || manifest.files[object.file].size !== object.size) throw new Error('BACKUP_OBJECT_INVALID');
  }
  if (!Array.isArray(manifest.materials) || new Set(manifest.materials.map(item => item.attemptId)).size !== manifest.materials.length) throw new Error('BACKUP_MATERIAL_INVALID');
  for (const material of manifest.materials) {
    if (!/^[0-9a-f-]{36}$/.test(material.attemptId) || typeof material.originalPath !== 'string'
        || material.archive !== 'evaluation-materials/' + material.attemptId
        || !expected.some(key => key.startsWith(material.archive + '/'))) throw new Error('BACKUP_MATERIAL_INVALID');
  }
  if (!isDeepStrictEqual(Object.keys(manifest.images).sort(), ['elasticsearch','minio','mysql'])) throw new Error('BACKUP_IMAGE_SET_INVALID');
  for (const image of Object.values(manifest.images)) {
    const local = JSON.parse((await docker(manifest.source, ['image', 'inspect', image.name])).stdout)[0];
    if (local.Id !== image.id) throw new Error('RESTORE_IMAGE_VERSION_UNSUPPORTED');
  }
  return manifest;
}

/** 全新隔离目标恢复；中断状态预先写 PARTIAL，禁止对半成品追加。 */
export async function restoreBackup(root, directory, target, {targetId, fault = async () => {}} = {}) {
  const manifest = await validateBackup(directory);
  if (targetId === manifest.source.deploymentId) throw new Error('RESTORE_SOURCE_RESOURCE_REUSE');
  await assertNoLinkAncestors(target);
  const sourceRelative = relative(resolve(manifest.source.directory), resolve(target));
  if (!sourceRelative || !sourceRelative.startsWith('..') && !/^[A-Za-z]:/.test(sourceRelative)) throw new Error('RESTORE_SOURCE_REUSE');
  try { await access(target); throw new Error('RESTORE_TARGET_NOT_EMPTY'); } catch (error) { if (error.code !== 'ENOENT') throw error; }
  const deployment = await createDeployment(root, target, {deploymentId: targetId, dockerContext: manifest.source.dockerContext});
  if (deployment.daemonId === manifest.source.daemonId && Object.values(deployment.resources).some(resource => Object.values(manifest.source.resources).some(source => resource.id === source.id || resource.volumes.some(volume => source.volumes.includes(volume))))) throw new Error('RESTORE_SOURCE_RESOURCE_REUSE');
  const progress = {schemaVersion: 1, backupId: manifest.backupId, deploymentId: deployment.deploymentId, status: 'PARTIAL', stage: 'CREATED'};
  await atomicJson(join(target, 'restore.json'), progress);
  try {
    const empty = await databaseSnapshot(deployment);
    if (Object.values(empty.schemas).some(tables => Object.keys(tables).length)) throw new Error('RESTORE_TARGET_NOT_EMPTY');
    if ((await minio(deployment).listBuckets()).length || Object.keys((await elasticSnapshot(deployment)).indices).length) throw new Error('RESTORE_TARGET_NOT_EMPTY');
    await logicalSql(deployment, 'restore', join(directory, 'databases.sql'));
    progress.stage = 'MYSQL'; await atomicJson(join(target, 'restore.json'), progress); await fault('MYSQL_IMPORTED', deployment);
    const client = minio(deployment);
    if (manifest.minio.exists) await client.makeBucket(manifest.minio.bucket);
    for (const object of manifest.minio.objects) {
      await client.fPutObject(manifest.minio.bucket, object.key, join(directory, object.file), object.metadata);
      if (object.tags && Object.keys(object.tags).length) await client.setObjectTagging(manifest.minio.bucket, object.key, object.tags);
    }
    progress.stage = 'MINIO'; await atomicJson(join(target, 'restore.json'), progress); await fault('MINIO_IMPORTED', deployment);
    const repository = 'rd-' + manifest.backupId;
    if (manifest.esRepository.indices.length) {
      await docker(deployment, ['exec', '-u', '0', deployment.resources.elasticsearch.id, 'mkdir', '-p', manifest.esRepository.location]);
      await docker(deployment, ['cp', join(directory, 'es-repository') + '/.', `${deployment.resources.elasticsearch.id}:${manifest.esRepository.location}`], {timeout: 60000});
      await docker(deployment, ['exec', '-u', '0', deployment.resources.elasticsearch.id, 'chown', '-R', '1000:0', manifest.esRepository.location]);
      await es(deployment, 'PUT', `/_snapshot/${repository}`, {type: 'fs', settings: {location: manifest.esRepository.location, readonly: true}});
      const response = await es(deployment, 'POST', `/_snapshot/${repository}/${manifest.esRepository.snapshot}/_restore?wait_for_completion=true`, {indices: manifest.esRepository.indices.join(','), include_global_state: false, include_aliases: true}, 120000);
      if (!response.snapshot || response.snapshot.shards.failed !== 0) throw new Error('ES_RESTORE_INCOMPLETE');
    }
    progress.stage = 'ELASTICSEARCH'; await atomicJson(join(target, 'restore.json'), progress);
    if (!isDeepStrictEqual(manifest.mysql, await databaseSnapshot(deployment))) throw new Error('RESTORE_DATABASE_MISMATCH');
    if (!isDeepStrictEqual(manifest.minio, await captureObjects(deployment))) throw new Error('RESTORE_OBJECT_MISMATCH');
    if (!isDeepStrictEqual(manifest.elastic, await elasticSnapshot(deployment))) throw new Error('RESTORE_INDEX_MISMATCH');
    // 原配置封存不改写；恢复配置只来自新部署，禁用外发及消费者，历史路径不重绑定。
    await copyFile(join(directory, 'application.jar'), join(target, 'application.jar'));
    await copyFile(join(directory, 'configuration/settings.json'), join(target, 'archived-model-settings.json'));
    await mkdir(join(target,'archived-configuration'));
    for(const key of Object.keys(manifest.files).filter(key=>key.startsWith('configuration/'))){
      await copyFile(await safeFile(directory,key),join(target,'archived-configuration',key.slice('configuration/'.length)));
    }
    deployment.externalDependencies={archive:manifest.files['configuration/external-dependencies.json']?'archived-configuration/external-dependencies.json':null,status:'NOT_REBOUND',packaged:false};
    deployment.archivedMaterials = [];
    for (const material of manifest.materials) {
      if (!/^[0-9a-f-]{36}$/.test(material.attemptId) || material.archive !== 'evaluation-materials/' + material.attemptId) throw new Error('BACKUP_MATERIAL_INVALID');
      const archive = 'evaluation-archive/' + material.attemptId;
      for (const key of Object.keys(manifest.files).filter(key => key.startsWith(material.archive + '/'))) {
        const destination = join(target, archive, key.slice(material.archive.length + 1));
        await mkdir(dirname(destination), {recursive: true}); await copyFile(await safeFile(directory, key), destination);
      }
      deployment.archivedMaterials.push({...material, archive});
    }
    deployment.restoreApplicationArgs=restoreArguments(target);
    deployment.jar = join(target, 'application.jar'); deployment.jarSha256 = manifest.jarSha256;
    await atomicJson(join(target, 'deployment.json'), deployment);
    progress.status = 'RESTORED_STOPPED'; progress.stage = 'VERIFIED_STORAGE'; progress.completedAt = new Date().toISOString();
    await atomicJson(join(target, 'restore.json'), progress); return {deployment, manifest, progress};
  } catch (error) {
    progress.errorCode = /^[A-Z_]+$/.test(error.message) ? error.message : 'RESTORE_FAILED';
    await atomicJson(join(target, 'restore.json'), progress); throw error;
  }
}
