import {mkdir,readFile,writeFile,copyFile,cp,readdir} from 'node:fs/promises';
import {createReadStream} from 'node:fs';
import {resolve,join,dirname} from 'node:path';
import {createHash} from 'node:crypto';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {fileURLToPath} from 'node:url';
const exec=promisify(execFile),root=resolve(fileURLToPath(new URL('../..',import.meta.url)));
const input=Object.fromEntries(process.argv.slice(2).map(arg=>{const match=/^--(jar|output)=(.+)$/.exec(arg);if(!match)throw new Error('INPUT_INVALID');return [match[1],match[2]];}));
const run=async(file,args,extra={})=>exec(file,args,{windowsHide:true,timeout:300000,maxBuffer:8*1024*1024,...extra});
async function hash(path){const digest=createHash('sha256');for await(const bytes of createReadStream(path))digest.update(bytes);return digest.digest('hex');}
async function inventory(base,folder=''){let result=[];for(const item of await readdir(join(base,folder),{withFileTypes:true})){const path=folder?`${folder}/${item.name}`:item.name;if(item.isSymbolicLink())throw new Error('BUNDLE_LINK_REJECTED');if(item.isDirectory())result.push(...await inventory(base,path));else result.push(path);}return result;}
try{
 const jar=resolve(input.jar),output=resolve(input.output);await mkdir(output,{mode:0o755});
 const jarHash=await hash(jar),buildImage=`supportops-enterprise-build:${jarHash.slice(0,16)}`;
 const files=['enterprise.yml','mysql-init.sh','minio-entrypoint.sh'];
 for(const name of files)await copyFile(join(root,'infrastructure/enterprise',name),join(output,name));
 await copyFile(join(root,'scripts/enterprise/initialize.mjs'),join(output,'initialize.mjs'));
 for(const name of ['runtime.mjs','backup.mjs','restore.mjs','ops.mjs'])await copyFile(join(root,'scripts/enterprise',name),join(output,name));
 await copyFile(jar,join(output,'application.jar'));
 // 构建只复制已列出的公开资产，不把工作区、本地配置或密钥发送给Docker。
 const build=join(output,'build');await mkdir(build);
 for(const name of ['application.jar','enterprise.yml','initialize.mjs'])await copyFile(join(output,name),join(build,name));
 const nodeImage=JSON.parse((await run('docker',['image','inspect','supportops-reliability-linux:20260929'])).stdout)[0].Id;
 let dockerfile=await readFile(join(root,'infrastructure/enterprise/Dockerfile'),'utf8');
 // 本地工具镜像只提供Node二进制；最终镜像保留JRE和公开程序，不携带工具箱。
 await writeFile(join(build,'Dockerfile'),dockerfile);
 await run('docker',['build','--pull=false','--network=none','--tag',buildImage,build]);
 // 交付构建同样使用固定官方源码，不能依赖打包机器偶然缓存的旧发行镜像。
 const minioImage='supportops-minio-source:0d7408fc9969caf0';
 await run('docker',['build','--tag',minioImage,join(root,'infrastructure/minio')],{timeout:1800000});
 const images={};
 for(const [key,image] of Object.entries({app:buildImage,mysql:'mysql:8.4',minio:minioImage,elasticsearch:'docker.elastic.co/elasticsearch/elasticsearch:8.19.4'})){
   const details=JSON.parse((await run('docker',['image','inspect',image])).stdout)[0];
   // 相同JAR的构建也可能生成不同镜像身份，交付标签按实际镜像ID固定，避免覆盖另一部署使用的标签。
   const lockedName=key==='app'?`supportops-enterprise:${details.Id.slice(7,23)}`:`supportops-dependency-${key}:${details.Id.slice(7,23)}`;
   await run('docker',['tag',details.Id,lockedName]);
   images[key]={name:lockedName,id:details.Id,registryDigest:details.RepoDigests?.[0]||null};
 }
 let compose=await readFile(join(root,'infrastructure/enterprise/compose.template.yml'),'utf8');
 for(const [key,value]of Object.entries(images))compose=compose.replace(`__${{app:'APP',mysql:'MYSQL',minio:'MINIO',elasticsearch:'ES'}[key]}_IMAGE__`,value.name);
 await writeFile(join(output,'compose.yml'),compose);
 await cp(join(root,'examples/integration'),join(output,'examples'),{recursive:true,errorOnExist:true,force:false});
 await mkdir(join(output,'source-classes'));
 const javaHome=process.env.JAVA_HOME;if(!javaHome)throw new Error('JDK21_REQUIRED');
 await run(join(javaHome,'bin',process.platform==='win32'?'javac.exe':'javac'),['--release','21','-encoding','UTF-8','-d',join(output,'source-classes'),join(root,'examples/integration/java-diagnostic-source/src/DiagnosticSource.java')]);
 await run(join(javaHome,'bin',process.platform==='win32'?'jar.exe':'jar'),['--create','--file',join(output,'diagnostic-source.jar'),'--main-class','DiagnosticSource','-C',join(output,'source-classes'),'.']);
 await run('docker',['save','--output',join(output,'images.tar'),...Object.values(images).map(value=>value.name)]);
 for(const name of ['enterprise-integration.md','enterprise-deployment.md','enterprise-source-protocol.md'])await copyFile(join(root,'docs',name),join(output,name));
 await copyFile(join(root,'scripts/enterprise/install.sh'),join(output,'install.sh'));
 await writeFile(join(output,'verify-images.sh'),'#!/bin/sh\nset -eu\n'+Object.values(images).map(value=>`test "$(docker image inspect --format '{{.Id}}' '${value.name}')" = '${value.id}' || { echo IMAGE_ID_MISMATCH >&2; exit 1; }`).join('\n')+'\n');
 const assets={};for(const path of await inventory(output))assets[path]=await hash(join(output,path));
 const manifest={schemaVersion:1,kind:'ENTERPRISE_BUNDLE_V1',jarSha256:jarHash,images,buildInputs:{nodeImage},files:assets,createdAt:new Date().toISOString(),offline:true};
 await writeFile(join(output,'manifest.json'),JSON.stringify(manifest,null,2)+'\n');
 await writeFile(join(output,'SHA256SUMS'),[...Object.entries(assets),['manifest.json',await hash(join(output,'manifest.json'))]].map(([path,digest])=>`${digest}  ${path}`).join('\n')+'\n');
 console.log(JSON.stringify({status:'PACKAGED',jarSha256:jarHash,image:images.app.name,files:Object.keys(assets).length,offline:true}));
}catch(error){console.error(JSON.stringify({status:'FAILED',code:/^[A-Z_]+$/.test(error.message)?error.message:'ENTERPRISE_PACKAGE_FAILED'}));process.exitCode=1;}
