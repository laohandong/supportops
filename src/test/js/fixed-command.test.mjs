import test from 'node:test';
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {canonicalCommand,createFixedCommandStore,initializeFixedCommands} from '../../../src/main/resources/static/fixed-command.js';

const storage=()=>{const values=new Map();return {getItem:key=>values.get(key)||null,setItem:(key,value)=>values.set(key,value),removeItem:key=>values.delete(key)};};
const body=()=>({requestId:randomUUID(),expectedVersion:1,draftHash:'a'.repeat(64)});
test('Unicode规范与Java共同夹具一致；属性排序而数组顺序保留',()=>{
  const value={z:' e\u0301\r\n ',a:'中文'};assert.equal(canonicalCommand(value),'{"a":"中文","z":"é"}');
  assert.notEqual(canonicalCommand({a:['一','二']}),canonicalCommand({a:['二','一']}));
});
test('冻结命令在刷新与双标签页保留方法、目标、正文和原键',()=>{
  const saved=storage(),first=createFixedCommandStore(saved,'deployment','user'),request=body();
  const command=first.freeze('/evaluations/case-library/id/publish','PUBLISH','id',request),second=createFixedCommandStore(saved,'deployment','user');
  assert.deepEqual(second.read(),command);assert.throws(()=>second.freeze('/evaluations/datasets/from-cases','DATASET','new',body()));
  assert.equal(second.confirm({requestId:request.requestId,httpStatus:201,kind:'PUBLISH',targetId:'other'}),false);
  assert.deepEqual(second.read(),command);assert.equal(second.confirm({requestId:request.requestId,httpStatus:201,kind:'PUBLISH',targetId:'id'}),true);assert.equal(first.read(),null);
});
test('存储异常保留当前内存命令并显示限制；确认前禁止创建新键',()=>{
  const broken={getItem(){throw Error('blocked');},setItem(){throw Error('blocked');},removeItem(){throw Error('blocked');}};
  const saved=createFixedCommandStore(broken,'deployment','user'),request=body(),frozen=saved.freeze('/evaluations/id','DRAFT','id',request);
  assert.equal(saved.limited,true);assert.equal(saved.read(),frozen);assert.throws(()=>saved.freeze('/evaluations/new','DRAFT','new',body()));
});
test('部署与账号命名空间隔离，登出清理不会操作其他账号命令',()=>{
  const shared=storage(),one=createFixedCommandStore(shared,'d1','u1'),two=createFixedCommandStore(shared,'d1','u2'),other=createFixedCommandStore(shared,'d2','u1');
  one.freeze('/evaluations/one','DRAFT','one',body());two.freeze('/evaluations/two','DRAFT','two',body());assert.equal(other.read(),null);one.logout();assert.equal(one.read(),null);assert.ok(two.read());
});
for(const code of ['401','404','NETWORK']) test('查询失败 '+code+' 不解锁或更换原命令',async()=>{
  const pending=storage(),calls=[],request=body();
  const api=async(path,options)=>{calls.push({path,options});if(path==='/auth/me')return {id:'user'};if(path==='/auth/command-scope')return {scope:'scope'};throw Error(code);};
  const commands=initializeFixedCommands({api,scope:'scope',userId:'user',storage:pending});
  await assert.rejects(commands.mutate('/evaluations/id/publish','PUBLISH','id',request));const saved=commands.store.read();assert.equal(saved.requestId,request.requestId);
  await assert.rejects(commands.recover());assert.deepEqual(commands.store.read(),saved);await assert.rejects(commands.retry());
  const posts=calls.filter(item=>item.options?.method==='POST');assert.equal(posts.length,2);assert.deepEqual(posts[0].options.body,posts[1].options.body);
});
test('迟到回执在账号变化后不可确认，页面重新初始化不会自动提交',async()=>{
  let identity='u1',release;const pending=storage(),calls=[];
  const api=async(path,options)=>{calls.push({path,options});if(path==='/auth/me')return {id:identity};if(path==='/auth/command-scope')return {scope:'scope'};
    if(options?.method==='POST')return new Promise(done=>{release=done;});return {requestId:request.requestId,httpStatus:201,kind:'PUBLISH',targetId:'id'};};
  const request=body(),commands=initializeFixedCommands({api,scope:'scope',userId:'u1',storage:pending}),submitted=commands.mutate('/evaluations/id/publish','PUBLISH','id',request);
  while(!release)await new Promise(done=>setTimeout(done,1));identity='u2';release({});await assert.rejects(submitted);assert.ok(commands.store.read());
  initializeFixedCommands({api,scope:'scope',userId:'u1',storage:pending});assert.equal(calls.filter(item=>item.options?.method==='POST').length,1);
});
