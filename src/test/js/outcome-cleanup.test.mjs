import test from 'node:test';
import assert from 'node:assert/strict';
import {confirmedStoppedWriters} from '../../../scripts/reliability/cleanup.mjs';

test('短暂连接退出后必须重新取得完整停写证明才允许调用者继续',async()=>{
  let attempts=0,time=0;
  const proof={applications:[{code:'PROCESS_EXITED'}],attempts:[]};
  const result=await confirmedStoppedWriters({deploymentId:'synthetic'}, {
    check:async()=>{attempts++;if(attempts===1)throw new Error('UNKNOWN_DATABASE_WRITER');return proof;},
    now:()=>time,wait:async milliseconds=>{time+=milliseconds;}
  });
  assert.equal(attempts,2);assert.deepEqual(result.proof,proof);assert.equal(result.rechecks.length,1);
  assert.equal(result.rechecks[0].errorCode,'UNKNOWN_DATABASE_WRITER');
});

test('未知写入者持续存在时原保护仍拒绝清理',async()=>{
  let time=0,attempts=0;
  await assert.rejects(confirmedStoppedWriters({}, {
    timeoutMs:2000,check:async()=>{attempts++;throw new Error('UNKNOWN_DATABASE_WRITER');},
    now:()=>time,wait:async milliseconds=>{time+=milliseconds;}
  }),/UNKNOWN_DATABASE_WRITER/);
  assert.equal(attempts,3);
});

test('活动应用及其他身份不明错误立即拒绝，不通过重试绕过',async()=>{
  for(const code of ['APPLICATION_NOT_CONFIRMED_STOPPED','WRITER_OWNERSHIP_UNKNOWN','EXECUTION_IDENTITY_UNKNOWN_OR_ACTIVE']) {
    let attempts=0;
    await assert.rejects(confirmedStoppedWriters({}, {
      check:async()=>{attempts++;throw new Error(code);},wait:async()=>assert.fail('不得等待重试')
    }),new RegExp(code));
    assert.equal(attempts,1);
  }
});
