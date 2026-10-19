import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import {mkdtemp,writeFile,readFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join,resolve} from 'node:path';
import {randomUUID,randomBytes} from 'node:crypto';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
const execute=promisify(execFile);
async function fixture(t){
 const directory=await mkdtemp(join(tmpdir(),'supportops-enterprise-client-')),state=join(directory,'request.json'),secret=join(directory,'access.secret');
 await writeFile(secret,`sop_${randomUUID()}_${randomBytes(32).toString('base64url')}`,{mode:0o600});
 const application=randomUUID(),run=randomUUID(),environment=randomUUID(),seen=[];
 let identity=application,queryStatus=404,post;
 const server=http.createServer(async(req,res)=>{
  const chunks=[];for await(const chunk of req)chunks.push(chunk);
  seen.push({method:req.method,path:req.url,body:Buffer.concat(chunks).toString()});
  res.setHeader('Content-Type','application/json');res.setHeader('X-SupportOps-Application-Id',identity);
  const reply=(status,body)=>{res.writeHead(status);res.end(JSON.stringify(body));};
  if(req.url.startsWith('/open-api/v1/requests/'))return reply(queryStatus,queryStatus===404?{error:'REQUEST_NOT_FOUND'}:{error:'READ_UNAVAILABLE'});
  if(req.method==='POST'&&req.url==='/open-api/v1/diagnoses'){
   post=JSON.parse(Buffer.concat(chunks).toString());return reply(202,{requestId:post.requestId,runId:run});
  }
  if(req.url.includes('/events?'))return reply(200,{events:[],nextAfter:0,hasMore:false});
  if(req.url.endsWith('/result'))return reply(200,{diagnosis:{runId:run,status:'COMPLETED'}});
  return reply(200,{runId:run,status:'COMPLETED',usage:{inputTokens:2,outputTokens:1,reported:true}});
 });await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));t.after(()=>new Promise(resolve=>server.close(resolve)));
 const invoke=async()=>execute(process.execPath,[resolve('examples/integration/client/diagnosis-client.mjs')],{
  timeout:20000,windowsHide:true,env:{...process.env,SUPPORTOPS_API_ORIGIN:`http://127.0.0.1:${server.address().port}`,
   SUPPORTOPS_SYNTHETIC_LOOPBACK:'true',SUPPORTOPS_CLIENT_STATE:state,SUPPORTOPS_APPLICATION_SECRET_FILE:secret,
   SUPPORTOPS_ENVIRONMENT_ID:environment,SUPPORTOPS_QUESTION:'合成客户端恢复验收'}});
 return {state,seen,invoke,setQueryStatus(value){queryStatus=value;},setIdentity(value){identity=value;},get post(){return post;}};
}
test('查询失败保持原键，确认404后提交已保存的相同请求体',async t=>{
 const value=await fixture(t);value.setQueryStatus(503);await assert.rejects(value.invoke());
 const original=JSON.parse(await readFile(value.state,'utf8'));assert.equal(original.status,'UNKNOWN');assert.equal(value.seen.filter(item=>item.method==='POST').length,0);
 value.setQueryStatus(404);await value.invoke();const saved=JSON.parse(await readFile(value.state,'utf8'));
 assert.equal(saved.requestId,original.requestId);assert.deepEqual(value.post,original.body);assert.equal(saved.status,'TERMINAL');assert.equal(value.seen.filter(item=>item.method==='POST').length,1);
});
test('同一状态更换应用身份后拒绝受理，不改原请求和应用',async t=>{
 const value=await fixture(t);await value.invoke();const original=JSON.parse(await readFile(value.state,'utf8'));
 value.setIdentity(randomUUID());await assert.rejects(value.invoke());const after=JSON.parse(await readFile(value.state,'utf8'));
 assert.equal(after.applicationId,original.applicationId);assert.equal(after.requestId,original.requestId);assert.equal(value.seen.filter(item=>item.method==='POST').length,1);
});
test('429读取不会产生新受理，下一次运行保留原状态',async t=>{
 const value=await fixture(t);value.setQueryStatus(429);await assert.rejects(value.invoke());
 const original=JSON.parse(await readFile(value.state,'utf8'));await assert.rejects(value.invoke());
 assert.equal(JSON.parse(await readFile(value.state,'utf8')).requestId,original.requestId);assert.equal(value.seen.filter(item=>item.method==='POST').length,0);
});
