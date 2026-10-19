import assert from 'node:assert/strict';
import {mkdir,writeFile,unlink} from 'node:fs/promises';
import {join,resolve} from 'node:path';
import {randomUUID} from 'node:crypto';
import {options,privateDirectory,atomicJson} from './reliability/common.mjs';
import {externalDependencies,validateExternalDependencies} from './reliability/external-dependencies.mjs';

/** 使用三个合成字节核对依赖摘要与缺失状态；不加载任何真实模型。 */
const input=options(process.argv.slice(2),['directory']);
const directory=resolve(input.directory||join('.local/reliability-dependencies',randomUUID()));
await privateDirectory(directory);await mkdir(join(directory,'public'));
const model=join(directory,'synthetic-model.bin'),config=join(directory,'application.env');
await writeFile(model,'abc',{flag:'wx',mode:0o600});
await writeFile(config,['SUPPORTOPS_RERANK_ENABLED=false',...['SUPPORTOPS_RERANK_MODEL_PATH','SUPPORTOPS_RERANK_TOKENIZER_PATH'].map(key=>key+'='+model)].join('\n'),{flag:'wx',mode:0o600});
const captured=await externalDependencies({directory,config});
assert.equal(captured.entries.length,2);
for(const entry of captured.entries){assert.equal(entry.state,'AVAILABLE_NOT_PACKAGED');assert.equal(entry.size,3);assert.equal(entry.sha256,'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad');assert.equal(entry.sourceFeatureEnabled,false);}
const snapshot=join(directory,'dependencies-private.json');await atomicJson(snapshot,captured);
assert.deepEqual(await validateExternalDependencies(snapshot),captured);
await unlink(model);
const missing=await externalDependencies({directory,config});assert.ok(missing.entries.every(entry=>entry.state==='MISSING'&&entry.size===null&&entry.sha256===null));
await atomicJson(snapshot,{...captured,entries:captured.entries.map(entry=>({...entry,sourcePath:'/historical/path/not/to/be/read'}))});
await validateExternalDependencies(snapshot);
await atomicJson(snapshot,{...captured,entries:captured.entries.map(entry=>({...entry,sha256:'bad'}))});
await assert.rejects(validateExternalDependencies(snapshot),/BACKUP_DEPENDENCIES_INVALID/);
await atomicJson(join(directory,'public/dependency-report.json'),{schemaVersion:1,platform:process.platform,status:'PASS',cases:[{id:'X01',status:'PASS',fixedKnownSha256:true,packaged:false},{id:'X02',status:'PASS',missingFileRecorded:true},{id:'X03',status:'PASS',invalidDigestRejected:true,historicalPathsRead:false}],realModelLoads:0});
console.log('Dependencies PASS');
