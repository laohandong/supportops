import {javaProbe} from './common.mjs';

/** 显式端口只接受十进制整数，未提供时保留 Spring 配置的决定权。 */
export function explicitPort(value) {
  if (value === undefined) return undefined;
  if (!/^\d+$/.test(value)) throw new Error('INPUT_INVALID');
  const port = Number(value);
  if (!Number.isInteger(port) || port > 65535) throw new Error('INPUT_INVALID');
  return port;
}

/** 使用最终 JAR 的正式 Spring 配置解析器，只读核对实际启动端口。 */
export async function configurationPort({java, jar, env, cwd, properties = [], args = []}) {
  const result = await javaProbe(java, jar, 'io.supportops.ops.RuntimeConfiguration', args, {env, cwd, properties, timeout: 15000});
  if (result.valid !== true || !Number.isInteger(result.port) || result.port < 0 || result.port > 65535) throw new Error('INPUT_INVALID');
  return result.port;
}
