import {readFile} from 'node:fs/promises';
import {inflateRawSync} from 'node:zlib';
import {sha256} from './runtime-safety.mjs';

/** 只读普通 ZIP 中的受限资源，不解压路径到文件系统，也不执行 JAR。 */
export async function jarResources(file) {
  const bytes = await readFile(file);
  if (bytes.length > 1024 * 1024 * 1024) throw new Error('TARGET_JAR_TOO_LARGE');
  let end = -1;
  for (let i = bytes.length - 22; i >= Math.max(0, bytes.length - 65557); i--) {
    if (bytes.readUInt32LE(i) === 0x06054b50) { end = i; break; }
  }
  if (end < 0) throw new Error('TARGET_JAR_INVALID');
  const entries = bytes.readUInt16LE(end + 10);
  let cursor = bytes.readUInt32LE(end + 16);
  const resources = {}, seen = new Set();
  for (let index = 0; index < entries; index++) {
    if (bytes.readUInt32LE(cursor) !== 0x02014b50) throw new Error('TARGET_JAR_INVALID');
    const method = bytes.readUInt16LE(cursor + 10), size = bytes.readUInt32LE(cursor + 20);
    const nameLength = bytes.readUInt16LE(cursor + 28), extra = bytes.readUInt16LE(cursor + 30), comment = bytes.readUInt16LE(cursor + 32);
    const local = bytes.readUInt32LE(cursor + 42);
    const name = bytes.subarray(cursor + 46, cursor + 46 + nameLength).toString('utf8').replace(/^BOOT-INF\/classes\//, '');
    cursor += 46 + nameLength + extra + comment;
    if (!/^(bundled-knowledge|prompts|skills)\//.test(name) || name.endsWith('/')) continue;
    if (seen.has(name) || name.includes('..') || name.includes('\\')) throw new Error('TARGET_RESOURCE_INVALID');
    seen.add(name);
    if (bytes.readUInt32LE(local) !== 0x04034b50) throw new Error('TARGET_JAR_INVALID');
    const start = local + 30 + bytes.readUInt16LE(local + 26) + bytes.readUInt16LE(local + 28);
    const compressed = bytes.subarray(start, start + size);
    const content = method === 0 ? compressed : method === 8 ? inflateRawSync(compressed, {maxOutputLength: 16 * 1024 * 1024}) : null;
    if (!content || content.length > 16 * 1024 * 1024) throw new Error('TARGET_RESOURCE_INVALID');
    resources[name] = sha256(content);
  }
  if (!resources['prompts/diagnosis.txt']) throw new Error('TARGET_PROMPT_MISSING');
  return {jarSha256: sha256(bytes), resources: Object.fromEntries(Object.entries(resources).sort(([a], [b]) => a.localeCompare(b)))};
}
