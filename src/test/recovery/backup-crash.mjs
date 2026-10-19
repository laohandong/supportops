import {writeFile} from 'node:fs/promises';
import {makeBackup} from '../../../scripts/reliability/backup-v1.mjs';
const [plan, deploymentId, output, signal] = process.argv.slice(2);
await makeBackup(plan, deploymentId, output, {fault: async stage => {
  if (stage !== 'SQL_EXPORTED') return;
  await writeFile(signal, 'SQL_EXPORTED');
  await new Promise(() => {});
}});
