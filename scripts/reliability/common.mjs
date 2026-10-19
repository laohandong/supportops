import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {createHash, randomUUID} from 'node:crypto';
import {mkdir, readFile, writeFile, rename, stat, lstat, readdir} from 'node:fs/promises';
import {resolve, dirname, join, relative, isAbsolute, sep} from 'node:path';

export const execute = promisify(execFile);
export const hash = bytes => createHash('sha256').update(bytes).digest('hex');
export const digest = async path => hash(await readFile(path));
export const javaExecutable = env => env.JAVA_HOME ? join(env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java';
export const pause = milliseconds => new Promise(done => setTimeout(done, milliseconds));

/** 错误信息不回显可能包含秘密的输入。 */
export function options(args, names, flags = []) {
  const result = {};
  for (const arg of args) {
    const match = /^--([a-z-]+)=(.+)$/.exec(arg);
    const key = match?.[1] ?? (arg.startsWith('--') ? arg.slice(2) : '');
    if (Object.hasOwn(result, key) || !(match ? names.includes(key) : flags.includes(key))) throw new Error('INPUT_INVALID');
    result[key] = match ? match[2] : true;
  }
  return result;
}

export async function atomicJson(path, value) {
  const temporary = `${path}.${randomUUID()}.tmp`;
  await writeFile(temporary, JSON.stringify(value, null, 2) + '\n', {mode: 0o600, flag: 'wx'});
  // Windows 的短暂共享锁可能拒绝替换；重试同一完整临时文件，不删除旧记录或改为原地覆盖。
  const deadline = Date.now() + 2000;
  for (;;) {
    try { await rename(temporary, path); return; }
    catch (error) {
      if (process.platform !== 'win32' || !['EPERM', 'EACCES', 'EBUSY'].includes(error.code) || Date.now() >= deadline) throw error;
      await pause(25);
    }
  }
}

/** 新目录独占创建；Windows 去继承后只授予当前 SID，POSIX 使用 0700。 */
export async function privateDirectory(path) {
  await assertNoLinkAncestors(path);
  await mkdir(dirname(path), {recursive: true});
  await mkdir(path, {mode: 0o700});
  if (process.platform === 'win32') {
    const identity = await execute('whoami.exe', ['/user', '/fo', 'csv', '/nh'], {windowsHide: true, timeout: 5000});
    const sid = /S-1-5-[0-9-]+/.exec(identity.stdout)?.[0];
    if (!sid) throw new Error('PRIVATE_ACL_IDENTITY_UNKNOWN');
    await execute('icacls.exe', [path, '/inheritance:r', '/grant:r', `*${sid}:(OI)(CI)F`], {windowsHide: true, timeout: 5000});
    const acl = await execute('icacls.exe', [path], {windowsHide: true, timeout: 5000});
    if (/\(I\)|S-1-1-0|Everyone|BUILTIN\\Users|Authenticated Users/i.test(acl.stdout)) throw new Error('PRIVATE_ACL_INVALID');
  } else if ((await stat(path)).mode & 0o077) throw new Error('PRIVATE_PERMISSIONS_INVALID');
  return path;
}

/** 创建及读取前检查整条祖先路径，目录链接也不能借绝对路径绕过范围。 */
export async function assertNoLinkAncestors(path) {
  let current = resolve(path);
  for (;;) {
    try { if ((await lstat(current)).isSymbolicLink()) throw new Error('BACKUP_LINK_INVALID'); }
    catch (error) { if (error.code !== 'ENOENT') throw error; }
    const parent = dirname(current);
    if (parent === current) break;
    current = parent;
  }
}

/** 可分享报告与私有目录分开；恢复只接受当前身份受保护的备份根。 */
export async function verifyPrivateDirectory(path) {
  await assertNoLinkAncestors(path);
  if (process.platform !== 'win32') {
    if ((await stat(path)).mode & 0o077) throw new Error('PRIVATE_PERMISSIONS_INVALID');
    return 'POSIX_0700';
  }
  const quoted = resolve(path).replaceAll("'", "''");
  const script = `$ErrorActionPreference='Stop'; $ProgressPreference='SilentlyContinue'; $p=Get-Acl -LiteralPath '${quoted}'; $u=[System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value; @($p.Access | ForEach-Object { [PSCustomObject]@{sid=$_.IdentityReference.Translate([System.Security.Principal.SecurityIdentifier]).Value; allow=$_.AccessControlType.ToString(); inherited=$_.IsInherited} }) | ConvertTo-Json -Compress; Write-Output ('CURRENT:'+ $u)`;
  // Windows PowerShell 5.1 不可继承 PowerShell 7 的模块目录，否则 Get-Acl 可能加载错误程序集。
  const environment = {...process.env, PSModulePath: join(process.env.SystemRoot || process.env.SYSTEMROOT, 'System32/WindowsPowerShell/v1.0/Modules')};
  let value;
  try { value = await execute('powershell.exe', ['-NoProfile', '-NonInteractive', '-EncodedCommand', Buffer.from(script, 'utf16le').toString('base64')], {env: environment, windowsHide: true, timeout: 5000}); }
  catch { throw new Error('PRIVATE_ACL_UNREADABLE'); }
  const lines = value.stdout.trim().split(/\r?\n/), sid = lines.at(-1)?.slice(8);
  const parsed = JSON.parse(lines.slice(0,-1).join('\n')), entries = Array.isArray(parsed) ? parsed : [parsed];
  if (!sid || !entries.length || entries.some(entry => entry.sid !== sid || entry.allow !== 'Allow')) throw new Error('PRIVATE_ACL_INVALID');
  return 'CURRENT_SID_ONLY';
}

export async function until(operation, milliseconds = 60000, code = 'CONDITION_TIMEOUT') {
  const deadline = Date.now() + milliseconds;
  while (Date.now() < deadline) {
    const value = await operation();
    if (value) return value;
    await pause(200);
  }
  throw new Error(code);
}

/** 独立主类不创建 Spring 容器，不传播原始异常。 */
export async function javaProbe(java, jar, main, args, {env, cwd, properties = [], timeout = 30000} = {}) {
  const response = await execute(java, [...properties, `-Dloader.main=${main}`, '-cp', jar,
    'org.springframework.boot.loader.launch.PropertiesLauncher', ...args], {env, cwd, windowsHide: true, timeout, maxBuffer: 2 * 1024 * 1024});
  const line = response.stdout.split(/\r?\n/).find(value => value.startsWith('OPS:'));
  if (!line) throw new Error('PROBE_PROTOCOL_INVALID');
  return JSON.parse(line.slice(4));
}

/** 恢复使用目录文件而不解包归档，逐层拒绝链接和越界。 */
export async function safeFile(root, key) {
  await assertNoLinkAncestors(root);
  if (typeof key !== 'string' || !key || isAbsolute(key) || key.includes('\\') || key.split('/').some(p => !p || p === '.' || p === '..')) throw new Error('BACKUP_PATH_INVALID');
  const path = resolve(root, key);
  if (!relative(root, path) || relative(root, path).startsWith('..' + sep)) throw new Error('BACKUP_PATH_INVALID');
  let current = resolve(root);
  if ((await lstat(current)).isSymbolicLink()) throw new Error('BACKUP_LINK_INVALID');
  for (const part of key.split('/')) {
    current = join(current, part);
    const metadata = await lstat(current);
    if (metadata.isSymbolicLink() || metadata.nlink > 1 && metadata.isFile()) throw new Error('BACKUP_LINK_INVALID');
  }
  return path;
}

export async function files(root, prefix = '') {
  const result = [];
  for (const item of await readdir(join(root, prefix), {withFileTypes: true})) {
    const key = prefix ? `${prefix}/${item.name}` : item.name;
    const metadata = await lstat(join(root, key));
    if (metadata.isSymbolicLink() || metadata.isFile() && metadata.nlink > 1) throw new Error('BACKUP_LINK_INVALID');
    if (item.isDirectory()) result.push(...await files(root, key));
    else if (item.isFile()) result.push(key);
    else throw new Error('BACKUP_FILE_TYPE_INVALID');
  }
  return result.sort();
}

export const check = (id, status, code, scope = 'LOCAL') => ({id, status, code, scope});
export const exitFor = checks => checks.some(c => c.status === 'FAIL') ? 1 : checks.some(c => c.status === 'UNKNOWN') ? 3 : 0;
export function printResult(result, json) {
  console.log(json ? JSON.stringify(result) : result.checks.map(c => `${c.status.padEnd(14)} ${c.id}: ${c.code} [${c.scope}]`).join('\n'));
}
