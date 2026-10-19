import mysql from 'mysql2/promise';
import {Client} from 'minio';
import {hash} from './common.mjs';
import http from 'node:http';

export async function connection(deployment) {
  return mysql.createConnection({host: '127.0.0.1', port: deployment.ports.mysql, user: 'root',
    password: 'synthetic-knowledge-root', connectTimeout: 4000, supportBigNumbers: true, bigNumberStrings: true, dateStrings: true});
}
export async function query(database, sql, params = []) {
  return (await database.query({sql, timeout: 8000}, params))[0];
}
/** mysql2 的 end Promise 只表示退出命令入队；等待服务端断开才继续停写证明。 */
export async function closeDatabase(database, timeout = 5000) {
  await new Promise((done, reject) => {
    let settled = false;
    const finish = error => {
      if (settled) return;
      settled = true; clearTimeout(timer); database.removeListener('end', ended); database.removeListener('error', failed);
      if (error) { database.destroy(); reject(error); } else done();
    };
    const ended = () => finish();
    const failed = () => finish(new Error('MYSQL_CLOSE_FAILED'));
    const timer = setTimeout(() => finish(new Error('MYSQL_CLOSE_UNCONFIRMED')), timeout);
    database.once('end', ended); database.once('error', failed);
    try { Promise.resolve(database.end()).catch(failed); } catch { failed(); }
  });
}
export function identifier(value) {
  if (!/^[A-Za-z][A-Za-z0-9_]{0,63}$/.test(value)) throw new Error('SQL_IDENTIFIER_INVALID');
  return '`' + value + '`';
}

/** 所有行与结构都进入私有内容摘要，不用总行数冒充数据一致。 */
export async function databaseSnapshot(deployment) {
  const database = await connection(deployment);
  try {
    const result = {version: (await query(database, 'SELECT VERSION() AS version'))[0].version, schemas: {}};
    for (const schema of [deployment.database, deployment.excel]) {
      const tables = await query(database, 'SELECT TABLE_NAME AS name FROM information_schema.tables WHERE TABLE_SCHEMA=? AND TABLE_TYPE=? ORDER BY TABLE_NAME', [schema, 'BASE TABLE']);
      const contents = {};
      for (const {name} of tables) {
        const rows = await query(database, `SELECT * FROM ${identifier(schema)}.${identifier(name)}`);
        if (rows.length > 250000) throw new Error('BACKUP_TABLE_SIZE_LIMIT');
        const structure = await query(database, `SHOW CREATE TABLE ${identifier(schema)}.${identifier(name)}`);
        const originalDdl = structure[0]['Create Table'];
        const defaultCharset = /DEFAULT CHARSET=([A-Za-z0-9_]+)/.exec(originalDdl)?.[1];
        // mysqldump 会显式展开原来继承的列字符集；只消除同默认字符集的展示差异。
        // 列类型、字符集、排序规则、默认值和生成表达式另按 information_schema 独立核对。
        const ddl = defaultCharset ? originalDdl.replace(new RegExp(' CHARACTER SET '+defaultCharset+'(?= COLLATE | NOT NULL| DEFAULT |,|$)','g'),'') : originalDdl;
        const columns = await query(database, 'SELECT COLUMN_NAME,ORDINAL_POSITION,COLUMN_DEFAULT,IS_NULLABLE,DATA_TYPE,CHARACTER_MAXIMUM_LENGTH,NUMERIC_PRECISION,NUMERIC_SCALE,DATETIME_PRECISION,CHARACTER_SET_NAME,COLLATION_NAME,COLUMN_TYPE,COLUMN_KEY,EXTRA,COLUMN_COMMENT,GENERATION_EXPRESSION FROM information_schema.columns WHERE TABLE_SCHEMA=? AND TABLE_NAME=? ORDER BY ORDINAL_POSITION', [schema,name]);
        const canonical = rows.map(row => JSON.stringify(row)).sort();
        contents[name] = {count: rows.length, sha256: hash(canonical.join('\n')), structureSha256: hash(JSON.stringify({ddl,columns}))};
      }
      result.schemas[schema] = contents;
    }
    return result;
  } finally { await closeDatabase(database); }
}

export function minio(deployment) {
  const transport={...http,request:(options,callback)=>{
    const request=http.request(options,callback);
    const deadline=setTimeout(()=>request.destroy(new Error('MINIO_REQUEST_TIMEOUT')),15000);
    request.once('close',()=>clearTimeout(deadline));return request;
  }};
  return new Client({endPoint: '127.0.0.1', port: deployment.ports.minio, useSSL: false, accessKey: 'synthetic-local', secretKey: 'synthetic-knowledge-minio',transport,retryOptions:{disableRetry:true}});
}
export async function objectList(client, bucket) {
  const items = [];
  for await (const item of client.listObjectsV2(bucket, '', true)) if (item.name) items.push(item);
  return items.sort((a, b) => a.name.localeCompare(b.name));
}
export async function objectBytes(client, bucket, key) {
  const parts = [];let bytes=0;
  for await (const part of await client.getObject(bucket, key)) {bytes+=part.length;if(bytes>2*1024*1024*1024)throw new Error('BACKUP_OBJECT_SIZE_LIMIT');parts.push(part);}
  return Buffer.concat(parts);
}
export async function es(deployment, method, path, body, timeout = 15000) {
  const response = await fetch(`http://127.0.0.1:${deployment.ports.elasticsearch}${path}`, {
    method, headers: {'Content-Type': 'application/json'}, ...(body === undefined ? {} : {body: JSON.stringify(body)}), signal: AbortSignal.timeout(timeout)});
  if (!response.ok) throw new Error('ES_REQUEST_FAILED');
  return response.json();
}

/** 精确索引与别名清单；只读获取映射、文档摘要，不包含集群全局设置。 */
export async function elasticSnapshot(deployment) {
  const cluster = await es(deployment, 'GET', '/');
  const all = await es(deployment, 'GET', '/_cat/indices?format=json&expand_wildcards=all');
  const indices = all.filter(item => !item.index.startsWith('.')).map(item => item.index).sort();
  if (indices.some(index => !index.startsWith(deployment.indexPrefix + '-'))) throw new Error('UNKNOWN_ES_RESOURCES');
  const result = {version: cluster.version.number, indices: {}};
  for (const index of indices) {
    const mapping = await es(deployment, 'GET', `/${index}/_mapping`);
    const aliases = await es(deployment, 'GET', `/${index}/_alias`);
    const response = await es(deployment, 'POST', `/${index}/_search`, {size: 10000, track_total_hits: true, query: {match_all: {}}});
    const total = response.hits?.total;
    // gte 表示下界，不能把被截断的总数或部分分片结果作为完整索引摘要。
    if (!total || total.relation !== 'eq' || !Number.isSafeInteger(total.value) || total.value < 0) throw new Error('BACKUP_INDEX_TOTAL_UNKNOWN');
    if (total.value > 10000) throw new Error('BACKUP_INDEX_SIZE_LIMIT');
    if (response.timed_out !== false || response._shards?.failed !== 0 || !Array.isArray(response.hits.hits)
        || response.hits.hits.length !== total.value) throw new Error('BACKUP_INDEX_QUERY_INCOMPLETE');
    const documents = response.hits.hits.map(hit => JSON.stringify({id: hit._id, source: hit._source})).sort();
    result.indices[index] = {count: documents.length, sha256: hash(documents.join('\n')), mappingSha256: hash(JSON.stringify(mapping[index].mappings)), aliases: aliases[index].aliases};
  }
  return result;
}
