import {createHash} from 'node:crypto';

/** 对原始字节计算摘要，供程序包与只读证据清单核对。 */
export const sha256 = value => createHash('sha256').update(value).digest('hex');

/** 对完整输出脱敏，同时覆盖 JSON 转义形式；调用方负责缓冲完整行。 */
export function redactor(secrets) {
  const values = [...new Set(secrets.filter(value => typeof value === 'string' && value)
    .flatMap(value => [value, JSON.stringify(value).slice(1, -1)]))].sort((a, b) => b.length - a.length);
  return text => values.reduce((output, value) => output.replaceAll(value, '[REDACTED]'), String(text));
}

/** 仅传递进程启动所需系统环境，禁止继承业务或模型凭据。 */
export function systemEnvironment(source = process.env) {
  return Object.fromEntries(Object.entries(source).filter(([key]) =>
    ['PATH', 'Path', 'SystemRoot', 'SYSTEMROOT', 'WINDIR', 'TEMP', 'TMP', 'HOME',
      'USERPROFILE', 'LOCALAPPDATA', 'APPDATA', 'COMSPEC', 'ComSpec', 'PATHEXT', 'JAVA_HOME'].includes(key)));
}
