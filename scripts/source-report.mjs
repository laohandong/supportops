import {readFile,readdir,mkdir} from 'node:fs/promises';
import {join,resolve} from 'node:path';
import {options,atomicJson} from './reliability/common.mjs';

/** 公开来源检查只导出白名单摘要，不复制应用日志、私密运行状态或配置。 */
const input=options(process.argv.slice(2),['directory','output']);
const root=resolve(input.directory||'.local/evaluation'),output=resolve(input.output||'.local/source-public');
const entries=[];
for(const item of await readdir(root,{withFileTypes:true})){
  if(!item.isDirectory())continue;
  try{
    const report=JSON.parse(await readFile(join(root,item.name,'sources/report.json'),'utf8'));
    const lifecycle=JSON.parse(await readFile(join(root,item.name,'sources/runtime.json'),'utf8'));
    if(report.kind!=='SOURCE_SMOKE')continue;
    entries.push({id:item.name,createdAt:report.createdAt,status:report.status,kind:report.kind,jarSha256:report.metadata?.jarSha256||lifecycle.jarSha256,
      cleanupStatus:lifecycle.cleanupStatus,cases:report.cases.map(value=>({id:value.id,status:value.status,
        environmentCheck:value.environmentCheck,retrievalCheck:value.retrievalCheck,sourceEvidencePresent:Boolean(value.sourceEvidence),modelDiagnosis:value.modelDiagnosis}))});
  }catch{}
}
if(!entries.length)throw new Error('SOURCE_REPORT_MISSING');
entries.sort((a,b)=>a.createdAt.localeCompare(b.createdAt));const latest=entries.at(-1);
await mkdir(output,{recursive:true});await atomicJson(join(output,'source-report.json'),latest);
console.log('SOURCE_PUBLIC_REPORT_CREATED');
