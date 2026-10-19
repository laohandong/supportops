import {resolve} from 'node:path';
import {pathToFileURL} from 'node:url';
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  console.error('EVALUATION_MOVED: 使用独立 AgentEval 的 scripts/evaluate.mjs，并显式登记 SupportOps 目标包。');
  process.exitCode=2;
}
