import {resolve} from 'node:path';
import {pathToFileURL} from 'node:url';

// 旧入口仅给出迁移说明；不启动评测、不转发请求、不读取工作台配置。
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
    console.error('评测已迁至独立 AgentEval 项目。请在该项目使用 node scripts/evaluate.mjs；旧入口退出码为 2。');
    process.exitCode = 2;
}
