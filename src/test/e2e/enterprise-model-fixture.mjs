import http from 'node:http';
import {readFileSync,writeFileSync,existsSync} from 'node:fs';
const secret=readFileSync('/private/model.secret','utf8'),receipt='/private/model-calls.json';
let calls=0,embeddingCalls=0;const save=()=>writeFileSync(receipt,JSON.stringify({calls,embeddingCalls}));save();
http.createServer(async(req,res)=>{
 if(!['/v1/chat/completions','/v1/embeddings'].includes(req.url)||req.method!=='POST'){res.writeHead(404).end();return;}
 if(req.headers.authorization!==`Bearer ${secret}`){res.writeHead(401).end();return;}
 const chunks=[];for await(const chunk of req)chunks.push(chunk);
 const body=JSON.parse(Buffer.concat(chunks).toString());
 if(req.url==='/v1/embeddings'){
  embeddingCalls++;save();const inputs=Array.isArray(body.input)?body.input:[body.input];
  while(existsSync('/private/embedding.pause'))await new Promise(resolve=>setTimeout(resolve,100));
  res.writeHead(200,{'Content-Type':'application/json'}).end(JSON.stringify({object:'list',model:'protocol-vector',
   data:inputs.map((value,index)=>({object:'embedding',index,embedding:[1,0,0,0,0,0,0,0]})),usage:{prompt_tokens:10,total_tokens:10}}));return;
 }
 calls++;save();
 let closed=false;res.on('close',()=>closed=true);
 while(existsSync('/private/model.pause')&&!closed)await new Promise(resolve=>setTimeout(resolve,100));
 if(closed)return;
 const hasTool=body.messages.some(message=>message.role==='tool');
 res.writeHead(200,{'Content-Type':'text/event-stream'});
 const frame=value=>res.write(`data: ${JSON.stringify({id:'isolated-fixture',object:'chat.completion.chunk',model:'protocol-fixture',...value})}\n\n`);
 if(!hasTool){
  frame({choices:[{index:0,delta:{role:'assistant',tool_calls:[{index:0,id:'fixture_tool',type:'function',function:{name:existsSync('/private/tool-choice')?readFileSync('/private/tool-choice','utf8').trim():'get_app_info',arguments:'{}'}}]}}]});
  frame({choices:[{index:0,delta:{},finish_reason:'tool_calls'}],usage:{prompt_tokens:20,completion_tokens:2,total_tokens:22}});
 }else{
  frame({choices:[{index:0,delta:{role:'assistant',content:'合成协议验收完成。请按现场来源核对系统身份；此夹具不验证模型诊断效果。'}}]});
  frame({choices:[{index:0,delta:{},finish_reason:'stop'}],usage:{prompt_tokens:25,completion_tokens:5,total_tokens:30}});
 }
 res.end('data: [DONE]\n\n');
}).listen(18500,'127.0.0.1',()=>console.log('SYNTHETIC_MODEL_READY'));
