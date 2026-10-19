import {createServer} from 'node:http';
import {randomUUID} from 'node:crypto';
import {pathToFileURL} from 'node:url';
import {resolve} from 'node:path';

/** 本地夹具通过实际流式工具调用取得证据；固定回答不代表模型质量。 */
export async function startEvaluationProvider(port = 0) {
    const requests = [], controls = {embeddingFailure: false, delayMs: 0, reportUsage: true};
    const server = createServer(async (request, response) => {
        let raw = '';
        for await (const part of request) raw += part;
        let body;
        try { body = raw ? JSON.parse(raw) : {}; } catch { response.writeHead(400).end(); return; }
        if (request.url === '/control' && request.method === 'POST') {
            Object.assign(controls, Object.fromEntries(Object.entries(body).filter(([key]) => Object.hasOwn(controls, key))));
            response.writeHead(200, {'Content-Type': 'application/json'}).end(JSON.stringify(controls)); return;
        }
        if (request.url === '/requests') { response.writeHead(200, {'Content-Type': 'application/json'}).end(JSON.stringify(requests)); return; }
        requests.push({path: request.url, body});
        if (request.url === '/v1/embeddings') {
            if (controls.embeddingFailure) { response.writeHead(503).end('{"error":"fixture_embedding_unavailable"}'); return; }
            const inputs = Array.isArray(body.input) ? body.input : [body.input];
            response.writeHead(200, {'Content-Type': 'application/json'}).end(JSON.stringify({data: inputs.map((input, index) => ({index, embedding: String(input).includes('sync.targetPath') ? [1, 0, 0] : [0, 1, 0]}))})); return;
        }
        if (request.url !== '/v1/chat/completions') { response.writeHead(404).end(); return; }
        if (controls.delayMs) await new Promise(done => setTimeout(done, controls.delayMs));
        const tools = (body.messages || []).filter(message => message.role === 'tool');
        const names = ['get_app_info', 'get_effective_config', 'get_recent_logs', 'get_downstream_health', 'search_knowledge'];
        let delta;
        if (tools.length < names.length) {
            const name = names[tools.length];
            delta = {role: 'assistant', tool_calls: [{index: 0, id: 'ec-' + randomUUID(), type: 'function', function: {name, arguments: name === 'search_knowledge' ? '{"query":"sync.targetPath"}' : '{}'}}]};
        } else {
            const evidence = tools.map(message => typeof message.content === 'string' ? message.content : JSON.stringify(message.content)).join('\n');
            const citation = /[0-9a-f-]{36}:[0-9]+/.exec(evidence)?.[0];
            delta = {role: 'assistant', content: '固定协议夹具响应，已读取现场与适用资料' + (citation ? ` [${citation}]` : '') + '。此响应仅验证工程流程，不代表真实模型效果。\n\n<script>window.ecUnsafeExecuted=true</script>\n```\n原始代码围栏\n```'};
        }
        const base = {id: 'fixture-' + randomUUID(), object: 'chat.completion.chunk', model: 'ec-protocol-fixture'};
        const chunk = {...base, choices: [{index: 0, delta}]};
        const end = {...base, choices: [{index: 0, delta: {}, finish_reason: delta.tool_calls ? 'tool_calls' : 'stop'}], ...(controls.reportUsage ? {usage: {prompt_tokens: 13, completion_tokens: 7, total_tokens: 20}} : {})};
        response.writeHead(200, {'Content-Type': 'text/event-stream'});
        response.end('data: ' + JSON.stringify(chunk) + '\n\ndata: ' + JSON.stringify(end) + '\n\ndata: [DONE]\n\n');
    });
    await new Promise((done,reject) => { server.once('error',reject); server.listen(port, '127.0.0.1',done); });
    return {server, requests, controls, port: server.address().port, close: () => new Promise(done => { server.close(done); server.closeAllConnections(); })};
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
    const provider = await startEvaluationProvider(Number(process.argv[2] || 0));
    console.log('LOCAL_PROTOCOL_FIXTURE_PORT=' + provider.port);
    const stop = async () => { await provider.close(); process.exitCode = 0; };
    process.on('SIGTERM', stop); process.on('SIGINT', stop);
}
