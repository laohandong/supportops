import {readFile, access} from 'node:fs/promises';
import {resolve} from 'node:path';
import {fileURLToPath, pathToFileURL} from 'node:url';
import {randomUUID} from 'node:crypto';
import {options, atomicJson, digest} from './reliability/common.mjs';
import {restoreBackup, validateBackup} from './reliability/backup-v2.mjs';

const root = resolve(fileURLToPath(new URL('..', import.meta.url)));
export async function restore(args = process.argv.slice(2)) {
  try {
    const input = options(args, ['backup', 'target', 'target-id', 'plan'], ['execute', 'json', 'help']);
    if (input.help) { console.log('restore: --backup=<trusted-private-directory> --target=<new-directory> --plan=<private-plan.json>; execute: --execute --plan=<plan> --target-id=<uuid>'); return 0; }
    if (input.execute) {
      if (!input.plan || !input['target-id'] || input.backup || input.target) throw new Error('INPUT_INVALID');
      const plan = JSON.parse(await readFile(resolve(input.plan), 'utf8'));
      if (plan.schemaVersion !== 1 || plan.kind !== 'FRESH_RESTORE_V1' || plan.targetId !== input['target-id']
          || await digest(resolve(plan.backup, 'manifest.json')) !== plan.manifestSha256) throw new Error('RESTORE_PLAN_INVALID');
      const result = await restoreBackup(root, plan.backup, plan.target, {targetId: plan.targetId});
      console.log(JSON.stringify({schemaVersion: 1, status: result.progress.status, deploymentId: result.deployment.deploymentId,
        backupId: result.manifest.backupId, jarSha256: result.manifest.jarSha256}));
    } else {
      if (!input.backup || !input.target || !input.plan || input['target-id']) throw new Error('INPUT_INVALID');
      const backup = resolve(input.backup), target = resolve(input.target);
      const manifest = await validateBackup(backup);
      try { await access(target); throw new Error('RESTORE_TARGET_NOT_EMPTY'); } catch (error) { if (error.code !== 'ENOENT') throw error; }
      const plan = {schemaVersion: 1, kind: 'FRESH_RESTORE_V1', targetId: randomUUID(), backupId: manifest.backupId,
        backup, target, manifestSha256: await digest(resolve(backup, 'manifest.json')), createdAt: new Date().toISOString()};
      await atomicJson(resolve(input.plan), plan);
      console.log(JSON.stringify({schemaVersion: 1, status: 'PLANNED_READ_ONLY', targetId: plan.targetId, backupId: plan.backupId, jarSha256: manifest.jarSha256}));
    }
    return 0;
  } catch (error) {
    const code = /^[A-Z][A-Z0-9_]+$/.test(error.message) ? error.message : 'RESTORE_FAILED';
    console.error(JSON.stringify({schemaVersion: 1, status: 'FAILED', code})); return code === 'INPUT_INVALID' ? 2 : 1;
  }
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) process.exitCode = await restore();
