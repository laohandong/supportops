import assert from 'node:assert/strict';
import {execute} from './common.mjs';

/** 只保存非秘密身份；容器环境、私密挂载正文和凭据不进入资源盘点。 */
export async function inventory(context=process.platform==='win32'?'desktop-linux':undefined) {
  const docker=args=>execute('docker',[...(context?['--context',context]:[]),...args],{windowsHide:true,timeout:15000,maxBuffer:16*1024*1024});
  const ids=(await docker(['ps','-aq','--no-trunc'])).stdout.trim().split(/\r?\n/).filter(Boolean);
  const containers=ids.length?JSON.parse((await docker(['inspect',...ids])).stdout).map(item=>({id:item.Id,name:item.Name,image:item.Image,created:item.Created,
    state:item.State.Status,labels:item.Config.Labels,ports:item.HostConfig.PortBindings,volumes:item.Mounts.filter(mount=>mount.Type==='volume').map(mount=>mount.Name).sort()})):[];
  const names=(await docker(['volume','ls','-q'])).stdout.trim().split(/\r?\n/).filter(Boolean);
  const volumes=names.length?JSON.parse((await docker(['volume','inspect',...names])).stdout).map(item=>({name:item.Name,driver:item.Driver,created:item.CreatedAt,labels:item.Labels})):[];
  return {schemaVersion:1,capturedAt:new Date().toISOString(),daemonId:(await docker(['info','--format','{{.ID}}'])).stdout.trim(),containers,volumes};
}

/** 旧资源身份、配置与运行状态全部保持；本轮允许新建对象另列，不按名称清理未知对象。 */
export function preservation(before,after) {
  assert.equal(after.daemonId,before.daemonId);
  const changed=[];
  for(const item of before.containers) {const actual=after.containers.find(value=>value.id===item.id);if(!actual)changed.push({kind:'container',id:item.id,reason:'MISSING'});else try{assert.deepEqual(actual,item);}catch{changed.push({kind:'container',id:item.id,reason:'IDENTITY_CONFIGURATION_OR_STATE_CHANGED'});}}
  for(const item of before.volumes) {const actual=after.volumes.find(value=>value.name===item.name);if(!actual)changed.push({kind:'volume',name:item.name,reason:'MISSING'});else try{assert.deepEqual(actual,item);}catch{changed.push({kind:'volume',name:item.name,reason:'IDENTITY_OR_CONFIGURATION_CHANGED'});}}
  return {status:changed.length?'FAIL':'PASS',changed,preservedContainers:before.containers.length-changed.filter(item=>item.kind==='container').length,
    preservedVolumes:before.volumes.length-changed.filter(item=>item.kind==='volume').length,
    addedContainers:after.containers.filter(item=>!before.containers.some(value=>value.id===item.id)),addedVolumes:after.volumes.filter(item=>!before.volumes.some(value=>value.name===item.name))};
}
