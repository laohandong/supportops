import {resolve} from 'node:path';
import {pathToFileURL} from 'node:url';
if(process.argv[1]&&import.meta.url===pathToFileURL(resolve(process.argv[1])).href){console.error('案例与执行验收已迁至 AgentEval 的 src/test/e2e；来源导出使用 SupportOps scripts/test-source-export.mjs。');process.exitCode=2;}
