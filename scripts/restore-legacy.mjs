import {readFile,access} from 'node:fs/promises';
import {resolve} from 'node:path';
import {fileURLToPath,pathToFileURL} from 'node:url';
import {randomUUID} from 'node:crypto';
import {options,atomicJson,digest} from './reliability/common.mjs';
import {restoreBackup,validateBackup} from './reliability/backup-v1.mjs';

/** 只恢复拆分前的整体 V1 备份到新部署；使用归档 JAR，不启动已迁走的业务写入。 */
export async function restoreLegacy(args=process.argv.slice(2)) {
  const root=resolve(fileURLToPath(new URL('..',import.meta.url)));
  const input=options(args,['backup','target','plan','target-id'],['execute','help']);
  if(input.help){console.log('旧整体 V1 备份：--backup=<private-directory> --target=<new-directory> --plan=<private-plan>; 执行：--execute --plan=<plan> --target-id=<uuid>');return 0;}
  if(input.execute){
    if(!input.plan||!input['target-id']||input.backup||input.target)throw new Error('INPUT_INVALID');
    const plan=JSON.parse(await readFile(resolve(input.plan),'utf8'));
    if(plan.kind!=='LEGACY_FRESH_RESTORE_V1'||plan.targetId!==input['target-id']||await digest(resolve(plan.backup,'manifest.json'))!==plan.manifestSha256)throw new Error('RESTORE_PLAN_INVALID');
    const result=await restoreBackup(root,plan.backup,plan.target,{targetId:plan.targetId});
    console.log(JSON.stringify({status:result.progress.status,deploymentId:result.deployment.deploymentId,kind:'LEGACY_FRESH_RESTORE_V1'}));
  }else{
    if(!input.backup||!input.target||!input.plan||input['target-id'])throw new Error('INPUT_INVALID');
    const backup=resolve(input.backup),target=resolve(input.target),manifest=await validateBackup(backup);
    try{await access(target);throw new Error('RESTORE_TARGET_NOT_EMPTY');}catch(error){if(error.code!=='ENOENT')throw error;}
    const plan={schemaVersion:1,kind:'LEGACY_FRESH_RESTORE_V1',targetId:randomUUID(),backupId:manifest.backupId,backup,target,manifestSha256:await digest(resolve(backup,'manifest.json'))};
    await atomicJson(resolve(input.plan),plan);console.log(JSON.stringify({status:'PLANNED_READ_ONLY',targetId:plan.targetId}));
  }
  return 0;
}
if(process.argv[1]&&import.meta.url===pathToFileURL(resolve(process.argv[1])).href){try{process.exitCode=await restoreLegacy();}catch(error){console.error(/^[A-Z_]+$/.test(error.message)?error.message:'LEGACY_RESTORE_FAILED');process.exitCode=1;}}
