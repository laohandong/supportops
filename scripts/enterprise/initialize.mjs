import {mkdir,writeFile,readFile,copyFile,chown,chmod} from 'node:fs/promises';
import {resolve,join} from 'node:path';
import {randomUUID,randomBytes,createHash} from 'node:crypto';
import {createReadStream} from 'node:fs';

const options=Object.fromEntries(process.argv.slice(2).map(value=>{const match=/^--([a-z][a-z0-9-]*)=(.+)$/.exec(value);if(!match)throw new Error('INPUT_INVALID');return [match[1],match[2]];}));
try {
  if(Object.keys(options).some(key=>!['directory','bundle','origin','tls-p12','tls-password-file','truststore-p12','truststore-password-file'].includes(key)))throw new Error('INPUT_INVALID');
  const directory=resolve(options.directory),bundle=resolve(options.bundle);
  const origin=new URL(options.origin);
  if(origin.protocol!=='https:'||origin.username||origin.password||origin.pathname!=='/'||origin.search||origin.hash)throw new Error('HTTPS_ORIGIN_REQUIRED');
  const manifest=JSON.parse(await readFile(join(bundle,'manifest.json'),'utf8'));
  if(manifest.kind!=='ENTERPRISE_BUNDLE_V1')throw new Error('BUNDLE_INVALID');
  for(const [path,expected] of Object.entries(manifest.files)){
    if(path.includes('..')||path.startsWith('/')||path.includes('\\'))throw new Error('BUNDLE_INVALID');
    const digest=createHash('sha256');for await(const data of createReadStream(join(bundle,path)))digest.update(data);
    if(digest.digest('hex')!==expected)throw new Error('BUNDLE_HASH_MISMATCH');
  }
  for(const source of [options['tls-p12'],options['tls-password-file'],options['truststore-p12'],options['truststore-password-file']]){
    if(!source)throw new Error('TLS_FILES_REQUIRED');const value=await readFile(resolve(source));if(!value.length||value.length>10*1024*1024)throw new Error('TLS_FILES_INVALID');
  }
  // 新目录独占创建；初始化永远不会替换已有部署或 Secret。
  await mkdir(directory,{mode:0o700});
  for(const name of ['secrets','tls','mysql-secrets'])await mkdir(join(directory,name),{mode:0o700});
  const deploymentId=randomUUID(),secret=()=>randomBytes(32).toString('base64url');
  const secrets={'mysql-root':secret(),'spring.datasource.password':secret(),
    'supportops.knowledge.excel.query-password':secret(),'supportops.knowledge.storage.secret-key':secret(),
    'setup-secret':secret(),'master-key.json':JSON.stringify({deploymentId,activeKeyId:'v1',keys:{v1:randomBytes(32).toString('base64')}})};
  for(const [path,value] of Object.entries(secrets))await writeFile(join(directory,'secrets',path),value,{flag:'wx',mode:0o600});
  // 固定MySQL镜像切换到UID 999后仍需读取这三项凭据，独立目录不暴露主密钥或TLS密码。
  const mysqlSecrets=['mysql-root','spring.datasource.password','supportops.knowledge.excel.query-password'];
  for(const name of mysqlSecrets)await writeFile(join(directory,'mysql-secrets',name),secrets[name],{flag:'wx',mode:0o600});
  for(const [name,source] of [['server.p12',options['tls-p12']],['truststore.p12',options['truststore-p12']]]){
    if(!source)throw new Error('TLS_FILES_REQUIRED');await copyFile(resolve(source),join(directory,'tls',name));
    await chmod(join(directory,'tls',name),0o600);
  }
  for(const [name,source] of [['server.ssl.key-store-password',options['tls-password-file']],['java-truststore-password',options['truststore-password-file']]]){
    if(!source)throw new Error('TLS_FILES_REQUIRED');await copyFile(resolve(source),join(directory,'secrets',name));
    await chmod(join(directory,'secrets',name),0o600);
  }
  for(const name of ['compose.yml','mysql-init.sh','minio-entrypoint.sh'])await copyFile(join(bundle,name),join(directory,name));
  await writeFile(join(directory,'.env'),`SUPPORTOPS_DEPLOYMENT_ID=${deploymentId}\nSUPPORTOPS_DEPLOYMENT_SHORT=${deploymentId.slice(0,8)}\nSUPPORTOPS_PUBLIC_ORIGIN=${origin.origin}\nSUPPORTOPS_HTTPS_PORT=${origin.port||443}\nSUPPORTOPS_BIND_IP=127.0.0.1\n`,{flag:'wx',mode:0o600});
  await writeFile(join(directory,'deployment.json'),JSON.stringify({kind:'ENTERPRISE_COMPOSE_V1',deploymentId,origin:origin.origin,jarSha256:manifest.jarSha256,bundleSha256:createHash('sha256').update(JSON.stringify(manifest)).digest('hex'),images:manifest.images,createdAt:new Date().toISOString()},null,2),{flag:'wx',mode:0o600});
  if(process.platform!=='win32'&&process.getuid?.()===0){
    await chown(join(directory,'mysql-secrets'),999,999);
    for(const name of mysqlSecrets)await chown(join(directory,'mysql-secrets',name),999,999);
    for(const folder of ['secrets','tls']){await chown(join(directory,folder),10001,10001);}
    for(const name of [...Object.keys(secrets),'server.ssl.key-store-password','java-truststore-password'])await chown(join(directory,'secrets',name),10001,10001);
    for(const name of ['server.p12','truststore.p12'])await chown(join(directory,'tls',name),10001,10001);
  }
  console.log(JSON.stringify({status:'INITIALIZED_STOPPED',deploymentId,secretLocation:'secrets/setup-secret',masterKeyLocation:'secrets/master-key.json'}));
} catch(error){console.error(JSON.stringify({status:'FAILED',code:/^[A-Z_]+$/.test(error.message)?error.message:'INITIALIZATION_FAILED'}));process.exitCode=1;}
