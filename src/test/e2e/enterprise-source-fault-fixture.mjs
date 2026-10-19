import https from 'node:https';
import {readFileSync,writeFileSync} from 'node:fs';
const secret=readFileSync('/private/source-one.secret','utf8'),password=readFileSync('/private/tls-password','utf8');
let calls=0;
https.createServer({pfx:readFileSync('/private/server.p12'),passphrase:password},(req,res)=>{
 calls++;writeFileSync('/private/source-fault-calls.json',JSON.stringify({calls}));
 const mode=readFileSync('/private/source-mode','utf8').trim();
 if(req.headers.authorization!=='Bearer '+secret){res.writeHead(401).end('synthetic-private');return;}
 if(['401','403','503'].includes(mode)){res.writeHead(Number(mode)).end('synthetic-private');return;}
 if(req.url!=='/v1/app-info'){res.writeHead(404).end('{}');return;}
 const body={protocolVersion:'1.0',requestId:req.headers['x-supportops-request-id'],target:{systemCode:'source-one',environmentCode:'test',productKey:'example-erp',productVersion:'2.0'},observedAt:new Date().toISOString(),source:{kind:'APPLICATION',instanceId:'optional-capability-source'},data:{applicationName:'仅必要能力的合成源',capabilities:['APP_INFO']}};
 res.writeHead(200,{'Content-Type':'application/json'}).end(JSON.stringify(body));
}).listen(9443,'0.0.0.0',()=>console.log('SYNTHETIC_SOURCE_READY'));
