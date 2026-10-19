import {createReadStream} from 'node:fs';
import {lstat,readFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {resolve} from 'node:path';
import {loadLocalConfig} from '../local-config.mjs';
import {assertNoLinkAncestors} from './common.mjs';

const keys=['SUPPORTOPS_RERANK_MODEL_PATH','SUPPORTOPS_RERANK_TOKENIZER_PATH'];
const defaults=['.local/models/reranker/model_quantized.onnx','.local/models/reranker/tokenizer.json'];
const limit=2*1024*1024*1024;

/** 外置权重只记录私密依赖事实；不打包、加载模型或重绑定源路径。 */
export async function externalDependencies(deployment){
  const environment=await loadLocalConfig(deployment.config,{}),entries=[];
  for(let index=0;index<keys.length;index++){
    const key=keys[index],sourcePath=resolve(deployment.directory,environment[key]||defaults[index]);
    await assertNoLinkAncestors(sourcePath);
    const entry={key,sourcePath,explicitlyConfigured:Boolean(environment[key]),sourceFeatureEnabled:/^true$/i.test(environment.SUPPORTOPS_RERANK_ENABLED||''),packaged:false};
    try{
      const metadata=await lstat(sourcePath);
      if(!metadata.isFile()||metadata.nlink>1)throw new Error('EXTERNAL_DEPENDENCY_FILE_UNSUPPORTED');
      if(metadata.size>limit)throw new Error('EXTERNAL_DEPENDENCY_SIZE_LIMIT');
      const digest=createHash('sha256');let bytes=0;
      for await(const part of createReadStream(sourcePath)){bytes+=part.length;if(bytes>limit)throw new Error('EXTERNAL_DEPENDENCY_SIZE_LIMIT');digest.update(part);}
      if(bytes!==metadata.size)throw new Error('EXTERNAL_DEPENDENCY_CHANGED');
      entries.push({...entry,state:'AVAILABLE_NOT_PACKAGED',size:bytes,sha256:digest.digest('hex')});
    }catch(error){if(error.code!=='ENOENT')throw error;entries.push({...entry,state:'MISSING',size:null,sha256:null});}
  }
  return {schemaVersion:1,kind:'EXTERNAL_FILE_DEPENDENCIES',packaged:false,entries};
}

/** 只验证封存记录格式；绝不读取备份内声明的源文件路径。 */
export async function validateExternalDependencies(path){
  const value=JSON.parse(await readFile(path,'utf8'));
  if(value.schemaVersion!==1||value.kind!=='EXTERNAL_FILE_DEPENDENCIES'||value.packaged!==false||!Array.isArray(value.entries)||value.entries.length!==keys.length)throw new Error('BACKUP_DEPENDENCIES_INVALID');
  for(const[index,entry]of value.entries.entries()){
    if(entry.key!==keys[index]||typeof entry.sourcePath!=='string'||!entry.sourcePath||entry.packaged!==false||typeof entry.explicitlyConfigured!=='boolean'||typeof entry.sourceFeatureEnabled!=='boolean')throw new Error('BACKUP_DEPENDENCIES_INVALID');
    if(entry.state==='MISSING'){if(entry.size!==null||entry.sha256!==null)throw new Error('BACKUP_DEPENDENCIES_INVALID');}
    else if(entry.state!=='AVAILABLE_NOT_PACKAGED'||!Number.isSafeInteger(entry.size)||entry.size<0||entry.size>limit||!/^[a-f0-9]{64}$/.test(entry.sha256))throw new Error('BACKUP_DEPENDENCIES_INVALID');
  }
  return value;
}
