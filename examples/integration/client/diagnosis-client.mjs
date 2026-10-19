import {readFile, writeFile, rename, open, unlink} from 'node:fs/promises';
import {resolve} from 'node:path';
import {randomUUID} from 'node:crypto';

// 密钥来自私有文件；状态仅保存原请求、应用身份和已读游标。
const file=resolve(process.env.SUPPORTOPS_CLIENT_STATE||'./supportops-request.json');
const url=new URL(process.env.SUPPORTOPS_API_ORIGIN);
if(url.username||url.password||url.pathname!=='/'||url.search||url.hash
    ||(url.protocol!=='https:'&&!(process.env.SUPPORTOPS_SYNTHETIC_LOOPBACK==='true'&&url.protocol==='http:'&&url.hostname==='127.0.0.1')))
    throw new Error('需要独立 HTTPS 来源；合成回环测试必须显式启用');
const token=(await readFile(process.env.SUPPORTOPS_APPLICATION_SECRET_FILE,'utf8')).trim();
if(!/^sop_[0-9a-f-]{36}_[A-Za-z0-9_-]{43}$/.test(token))throw new Error('访问密钥格式无效');
let lock;
try{lock=await open(file+'.lock','wx',0o600);}
catch{throw new Error('同一请求状态正在使用或上次异常退出；核实原客户端已退出后处理锁文件');}
let state;
const save=async()=>{
    const temporary=file+'.'+randomUUID()+'.tmp';
    await writeFile(temporary,JSON.stringify(state,null,2)+'\n',{flag:'wx',mode:0o600});
    try{await rename(temporary,file);}finally{await unlink(temporary).catch(()=>{});}
};
const request=async(path,options={})=>{
    const response=await fetch(new URL(path,url.origin),{...options,redirect:'error',signal:AbortSignal.timeout(15000),
        headers:{Authorization:`Bearer ${token}`,...(options.body?{'Content-Type':'application/json'}:{})}});
    const application=response.headers.get('X-SupportOps-Application-Id');
    if(application){
        if(state.applicationId&&state.applicationId!==application)throw new Error('原应用身份不同，禁止跨应用重新受理');
        if(!state.applicationId){state.applicationId=application;await save();}
    }
    if([200,202,404].includes(response.status)&&!application)throw new Error('应用身份响应缺失，保持原请求');
    const reader=response.body.getReader();let size=0;const chunks=[];
    try{while(true){const {done,value}=await reader.read();if(done)break;size+=value.length;
        if(size>2*1024*1024)throw new Error('响应超出读取上限');chunks.push(value);}}
    finally{await reader.cancel().catch(()=>{});}
    return {status:response.status,body:JSON.parse(Buffer.concat(chunks).toString('utf8')),retryAfter:response.headers.get('Retry-After')};
};
const accepted=response=>{
    if(![200,202].includes(response.status))throw new Error(`请求未确认：${response.body.error||response.body.code||response.status}${response.retryAfter?`，请至少等待 ${response.retryAfter} 秒`:''}`);
    return response.body;
};
try{
    try{state=JSON.parse(await readFile(file,'utf8'));}
    catch(error){
        if(error.code!=='ENOENT')throw new Error('原状态无法读取，禁止产生新请求');
        if(!process.env.SUPPORTOPS_ENVIRONMENT_ID||!process.env.SUPPORTOPS_QUESTION)throw new Error('首次调用需要环境 UUID 和问题');
        const requestId=randomUUID();
        state={origin:url.origin,requestId,body:{requestId,environmentId:process.env.SUPPORTOPS_ENVIRONMENT_ID,
            question:process.env.SUPPORTOPS_QUESTION,lexicalOnly:true,externalReference:null},status:'UNKNOWN',after:0};
        await writeFile(file,JSON.stringify(state,null,2)+'\n',{flag:'wx',mode:0o600});
    }
    if(state.origin!==url.origin||state.requestId!==state.body.requestId||!Number.isSafeInteger(state.after)||state.after<0)
        throw new Error('原请求部署、身份或游标不一致，保持原状态');
    if(!state.receipt){
        // 查询失败保持 UNKNOWN；只有确认404才重发已保存的同一请求体。
        let response=await request(`/open-api/v1/requests/${state.requestId}`);
        if(response.status===404)response=await request('/open-api/v1/diagnoses',{method:'POST',body:JSON.stringify(state.body)});
        const receipt=accepted(response);
        if(receipt.requestId!==state.requestId)throw new Error('原回执身份不匹配');
        state.receipt=receipt;state.status='ACCEPTED';await save();
    }
    const path=`/open-api/v1/diagnoses/${state.receipt.runId}`;
    if(process.env.SUPPORTOPS_CANCEL==='true')accepted(await request(path+'/cancel',{method:'POST'}));
    const deadline=Date.now()+120000;
    while(Date.now()<deadline){
        const page=accepted(await request(`${path}/events?after=${state.after}&limit=100`));
        if(!Number.isSafeInteger(page.nextAfter)||page.nextAfter<state.after)throw new Error('事件游标无效');
        for(const event of page.events){if(event.id<=state.after||event.id>page.nextAfter)throw new Error('事件顺序无效');}
        state.after=page.nextAfter;await save();
        if(page.events.length)console.log(JSON.stringify({runId:state.receipt.runId,eventIds:page.events.map(event=>event.id),after:state.after}));
        if(page.hasMore)continue;
        const diagnosis=accepted(await request(path));
        if(!['QUEUED','RUNNING'].includes(diagnosis.status)){
            state.result=accepted(await request(path+'/result'));state.status='TERMINAL';await save();
            console.log(JSON.stringify({requestId:state.requestId,runId:state.receipt.runId,status:diagnosis.status,errorCode:diagnosis.errorCode,usage:diagnosis.usage}));
            break;
        }
        await new Promise(resolve=>setTimeout(resolve,1500));
    }
    if(state.status!=='TERMINAL')console.log(JSON.stringify({status:'WAITING',requestId:state.requestId,runId:state.receipt.runId,after:state.after}));
}catch(error){console.error(JSON.stringify({status:state?.receipt?'READ_UNCERTAIN':'UNKNOWN',message:error.message,requestId:state?.requestId}));process.exitCode=1;}
finally{await lock.close();await unlink(file+'.lock');}
