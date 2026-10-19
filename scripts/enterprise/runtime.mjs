import {readFile,writeFile,mkdir,readdir,stat,chmod} from 'node:fs/promises';
import {createReadStream} from 'node:fs';
import {createHash} from 'node:crypto';
import {resolve,join} from 'node:path';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
export const run=async(args,options={})=>promisify(execFile)('docker',args,{windowsHide:true,timeout:300000,maxBuffer:4*1024*1024,...options});
export const hash=async file=>{const digest=createHash('sha256');for await(const value of createReadStream(file))digest.update(value);return digest.digest('hex');};
export const parse=args=>Object.fromEntries(args.map(arg=>{const value=/^--([a-z][a-z0-9-]*)=(.+)$/.exec(arg);if(!value)throw new Error('INPUT_INVALID');return [value[1],value[2]];}));
export const writePrivate=async(file,value)=>{await writeFile(file,value,{flag:'wx',mode:0o600});await chmod(file,0o600);};
export const volumes=['mysql-data','minio-data','es-data','application-data'];
export async function deployment(directory,{fresh=false}={}){
 directory=resolve(directory);const metadata=JSON.parse(await readFile(join(directory,'deployment.json'),'utf8'));
 if(metadata.kind!=='ENTERPRISE_COMPOSE_V1'||!/^[a-f0-9-]{36}$/.test(metadata.deploymentId))throw new Error('DEPLOYMENT_INVALID');
 const config=JSON.parse((await run(['compose','--project-directory',directory,'-f',join(directory,'compose.yml'),'config','--format','json'])).stdout);
 const expectedProject=`supportops-${metadata.deploymentId.slice(0,8)}`;
 if(config.name!==expectedProject)throw new Error('DEPLOYMENT_PROJECT_MISMATCH');
 for(const [key,identity]of Object.entries(metadata.images)){
  if(config.services[key]?.image!==identity.name)throw new Error('DEPLOYMENT_IMAGE_CONFIG_MISMATCH');
  const actual=JSON.parse((await run(['image','inspect',identity.name])).stdout)[0];
  if(actual.Id!==identity.id)throw new Error('DEPLOYMENT_IMAGE_MISMATCH');
 }
 const ids=(await run(['compose','--project-directory',directory,'-f',join(directory,'compose.yml'),'ps','--all','--quiet'])).stdout.trim().split(/\s+/).filter(Boolean);
 const containers=ids.length?JSON.parse((await run(['inspect',...ids])).stdout):[];
 if(containers.length!==4)throw new Error('DEDICATED_CONTAINERS_REQUIRED');
 for(const container of containers){
  if(container.Config.Labels?.['io.supportops.deployment']!==metadata.deploymentId
   ||container.Config.Labels?.['com.docker.compose.project']!==expectedProject)throw new Error('CONTAINER_OWNERSHIP_MISMATCH');
  const kind=container.Config.Labels['com.docker.compose.service'];
  if(!metadata.images[kind]||container.Image!==metadata.images[kind].id)throw new Error('CONTAINER_IMAGE_MISMATCH');
  if(fresh&&(container.State.Running||!container.State.StartedAt.startsWith('0001-')))throw new Error('TARGET_ALREADY_STARTED');
 }
 const volumeIds={};
 for(const logical of volumes){
  const name=config.volumes[logical]?.name;if(!name||name!==`${expectedProject}_${logical}`)throw new Error('VOLUME_NAME_MISMATCH');
  const identity=JSON.parse((await run(['volume','inspect',name])).stdout)[0];
  if(identity.Labels?.['com.docker.compose.project']!==expectedProject||identity.Labels?.['com.docker.compose.volume']!==logical)
   throw new Error('VOLUME_OWNERSHIP_MISMATCH');
  const attached=(await run(['ps','--all','--quiet','--filter',`volume=${name}`])).stdout.trim().split(/\s+/).filter(Boolean);
  if(attached.some(id=>!containers.some(container=>container.Id.startsWith(id))))throw new Error('VOLUME_SHARED_WITH_OTHER_CONTAINER');
  volumeIds[logical]={name,createdAt:identity.CreatedAt};
 }
 const compose=async args=>run(['compose','--project-directory',directory,'-f',join(directory,'compose.yml'),...args]);
 return {directory,metadata,containers,volumeIds,compose};
}
export async function stopped(state){
 const actual=JSON.parse((await run(['inspect',...state.containers.map(value=>value.Id)])).stdout);
 if(actual.some(value=>value.State.Running||value.State.Paused||value.State.OOMKilled
  ||value.State.StartedAt.startsWith('0001-')||![0,143].includes(value.State.ExitCode)))throw new Error('DEPLOYMENT_NOT_CLEANLY_STOPPED');
 return actual;
}
export async function helper(state,mounts,entrypoint,args){
 const command=['run','--rm','--network','none','--user','0:0','--label',`io.supportops.deployment=${state.metadata.deploymentId}`,
  '--label','io.supportops.kind=ENTERPRISE_MAINTENANCE'];
 for(const mount of mounts)command.push('--mount',mount);
 command.push('--entrypoint',entrypoint,state.metadata.images.app.name,...args);
 return run(command);
}
export async function verifyBackup(directory){
 directory=resolve(directory);const file=join(directory,'manifest.json'),metadata=JSON.parse(await readFile(file,'utf8'));
 if(metadata.kind!=='ENTERPRISE_COLD_BACKUP_V1'||!metadata.complete||metadata.masterKeyIncluded!==false)throw new Error('BACKUP_INVALID');
 if((await stat(file)).size>65536)throw new Error('BACKUP_INVALID');
 for(const [path,digest]of Object.entries(metadata.files)){
  if(!/^[a-z0-9.-]+$/.test(path)||!digest.match(/^[a-f0-9]{64}$/)||await hash(join(directory,path))!==digest)throw new Error('BACKUP_HASH_MISMATCH');
 }
 const files=await readdir(directory);if(files.some(file=>file!=='manifest.json'&&!Object.hasOwn(metadata.files,file)))throw new Error('BACKUP_UNEXPECTED_FILE');
 return {directory,metadata,manifestSha256:await hash(file)};
}
