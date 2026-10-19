import {dirname, resolve, join} from 'node:path';
import {fileURLToPath, pathToFileURL} from 'node:url';
import {loadLocalConfig} from './local-config.mjs';
import {options} from './reliability/common.mjs';
import {launch} from './reliability/runtime.mjs';
import {readFile} from 'node:fs/promises';
import {verifyDeployment} from './reliability/deployment.mjs';
import {restoreArguments} from './reliability/maintenance.mjs';
import {explicitPort} from './reliability/port.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');

/** 默认入口保持兼容；隔离路径、端口、设置与提供商来源显式选择。 */
export async function start(args = process.argv.slice(2)) {
  try {
    const input = options(args, ['config', 'jar', 'runtime-root', 'workdir', 'settings', 'providers', 'port', 'deployment']);
    let deployment;
    if(input.deployment){
      deployment=JSON.parse(await readFile(resolve(root,input.deployment),'utf8'));await verifyDeployment(deployment);
      try{
        const progress=JSON.parse(await readFile(join(deployment.directory,'restore.json'),'utf8'));
        if(progress.status!=='RESTORED_STOPPED')throw new Error('RESTORE_PARTIAL_START_REFUSED');
      }catch(error){if(error.code!=='ENOENT')throw error;}
    }
    const safeArgs=deployment?.restoreApplicationArgs?restoreArguments(deployment.directory):[];
    const previousSafeArgs=safeArgs.filter(argument=>argument!=='--supportops.rerank.enabled=false');
    if(deployment?.restoreApplicationArgs&&JSON.stringify(deployment.restoreApplicationArgs)!==JSON.stringify(safeArgs)&&JSON.stringify(deployment.restoreApplicationArgs)!==JSON.stringify(previousSafeArgs))throw new Error('RESTORE_PROFILE_INVALID');
    const env = await loadLocalConfig(resolve(root, input.config || deployment?.config || '.local/model.env'));
    const runtime = await launch({root, env, jar: resolve(root, input.jar || deployment?.jar || 'target/supportops-0.1.0-SNAPSHOT.jar'),
      runtimeRoot: resolve(root, input['runtime-root'] || deployment?.runtimeRoot || '.local/runtime'), cwd: resolve(root, input.workdir || deployment?.directory || '.'),
      settings: input.settings?resolve(root,input.settings):deployment?.settings,
      providers: input.providers === undefined ? (deployment?.providers||undefined) : input.providers === 'none' ? '' : resolve(root, input.providers),
      port: explicitPort(input.port), deployment: input.deployment && resolve(root, input.deployment),applicationArgs:safeArgs});
    console.log(`READY runtimeId=${runtime.record.runtimeId} port=${runtime.record.port} jarSha256=${runtime.record.jarSha256}`);
    console.log(`Runtime record: ${join(runtime.directory, 'runtime.json')}`);
    const stop = () => runtime.stop().catch(() => { console.error('STOP_IDENTITY_UNCONFIRMED'); process.exitCode = 3; });
    process.on('SIGINT', stop); process.on('SIGTERM', stop);
    await runtime.completion;
    process.off('SIGINT', stop); process.off('SIGTERM', stop);
    process.exitCode = runtime.record.phase === 'FAILED' ? 1 : 0;
  } catch (error) {
    console.error(error.code === 'ENOENT' ? 'JDK_OR_JAR_NOT_FOUND' : /^[A-Z][A-Z0-9_]+$/.test(error.message) ? error.message : 'START_FAILED');
    process.exitCode = error.message === 'INPUT_INVALID' ? 2 : 1;
  }
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) await start();
