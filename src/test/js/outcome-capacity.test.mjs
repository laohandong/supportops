import test from 'node:test';
import assert from 'node:assert/strict';
import {capacityCheck} from '../../../scripts/reliability/capacity.mjs';

test('容量不足时拒绝新建测试存储，保留已有实例',async()=>{
  await assert.rejects(capacityCheck({snapshot:{containers:[]},availableBytes:1024**3}),/MEMORY_HEADROOM/);
});
test('按部署去重且忽略停止实例，允许恢复源和目标并存',async()=>{
  const containers=['a','a','a','b'].map(id=>({state:'running',labels:{'io.supportops.deployment':id}}));
  containers.push({state:'exited',labels:{'io.supportops.deployment':'c'}});
  const proof=await capacityCheck({snapshot:{containers},availableBytes:8*1024**3,diskAvailableBytes:16*1024**3});
  assert.deepEqual(proof.runningDeployments,['a','b']);
});
test('执行盘不足时在创建任何存储前拒绝，恢复仍保留独立余量',async()=>{
  const input={snapshot:{containers:[]},availableBytes:8*1024**3,diskAvailableBytes:3*1024**3};
  await assert.rejects(capacityCheck(input),/DISK_HEADROOM/);
  assert.equal((await capacityCheck({...input,minimumDiskBytes:2*1024**3})).diskAvailableBytes,3*1024**3);
});
test('三套存储仍运行时拒绝累积第四套',async()=>{
  const containers=['a','b','c'].map(id=>({state:'running',labels:{'io.supportops.deployment':id}}));
  await assert.rejects(capacityCheck({snapshot:{containers},availableBytes:16*1024**3}),/CAPACITY_EXCEEDED/);
});
