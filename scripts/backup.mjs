import {readFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {pathToFileURL} from 'node:url';
import {options} from './reliability/common.mjs';
import {backupPlan, makeBackup} from './reliability/backup-v2.mjs';

export async function backup(args = process.argv.slice(2)) {
  try {
    const input = options(args, ['deployment', 'deployment-id', 'plan', 'output'], ['execute', 'json', 'help']);
    if (input.help) { console.log('backup: --deployment=<deployment.json> --plan=<private-plan.json>; execute: --execute --plan=<plan> --deployment-id=<uuid> --output=<new-private-directory>'); return 0; }
    if (input.execute) {
      if (!input.plan || !input['deployment-id'] || !input.output || input.deployment) throw new Error('INPUT_INVALID');
      const manifest = await makeBackup(resolve(input.plan), input['deployment-id'], resolve(input.output));
      console.log(JSON.stringify({schemaVersion: 1, status: manifest.status, backupId: manifest.backupId, jarSha256: manifest.jarSha256}));
    } else {
      if (!input.deployment || !input.plan || input.output || input['deployment-id']) throw new Error('INPUT_INVALID');
      const deployment = JSON.parse(await readFile(resolve(input.deployment), 'utf8'));
      const plan = await backupPlan(deployment, resolve(input.plan));
      console.log(JSON.stringify({schemaVersion: 1, status: 'PLANNED_READ_ONLY', planId: plan.planId, deploymentId: plan.deploymentId, jarSha256: plan.jarSha256}));
    }
    return 0;
  } catch (error) {
    const code = /^[A-Z][A-Z0-9_]+$/.test(error.message) ? error.message : 'BACKUP_FAILED';
    console.error(JSON.stringify({schemaVersion: 1, status: 'FAILED', code})); return code === 'INPUT_INVALID' ? 2 : 1;
  }
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) process.exitCode = await backup();
