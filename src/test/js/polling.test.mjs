import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';
import {validChunkOptions, batchChunkOptions, batchChunkLabel} from '../../main/resources/static/chunk-settings.mjs';

// Execute the browser controller with a minimal DOM fixture; all network responses are test data.
const knowledgeSource = (await readFile(new URL('../../main/resources/static/knowledge.js', import.meta.url), 'utf8'))
    .replace("import {validChunkOptions, batchChunkOptions, batchChunkLabel} from './chunk-settings.mjs';", '');
const usageSource = (await readFile(new URL('../../main/resources/static/usage-charts.js', import.meta.url), 'utf8')).replaceAll('export function ', 'function ');
const source = usageSource + '\n' + knowledgeSource.replace('export function initializeKnowledge', 'function initializeKnowledge') + '\n'
    + (await readFile(new URL('../../main/resources/static/app.js', import.meta.url), 'utf8')).replace("import {initializeKnowledge} from './knowledge.js';", '').replace("import {renderUsageDashboard} from './usage-charts.js';", '');
const markdownLibrary = await readFile(new URL('../../main/resources/static/vendor/markdown-it.min.js', import.meta.url), 'utf8');
const markdownSource = await readFile(new URL('../../main/resources/static/markdown.js', import.meta.url), 'utf8');

function fixture() {
    const nodes = new Map(), timers = [], frames = [];
    const calls = [];

    function node() {
        return {
            textContent: '',
            hidden: true,
            disabled: false,
            value: '',
            className: '',
            children: [],
            dataset: {},
            style: {},
            scrollIntoView() {},
            classList: {
                remove() {},
                toggle() {
                }
            },
            append(...values) {
                this.children.push(...values);
            },
            replaceChildren(...values) {
                this.children = values;
            },
            insertBefore(value, reference) {
                this.children.splice(this.children.indexOf(reference), 0, value);
            },
            addEventListener() {
            },
            querySelector() {
                return null;
            },
            setAttribute() {
            },
            removeAttribute() {
            }
        };
    }

    const get = (selector) => {
        if (!nodes.has(selector)) nodes.set(selector, node());
        return nodes.get(selector);
    };
    const env = {
        validChunkOptions, batchChunkOptions, batchChunkLabel,
        document: {body: node(), querySelector: get, querySelectorAll: selector => selector === '#evidence > [data-evidence-category]' ? get('#evidence').children.filter(child => child.dataset?.evidenceCategory) : [], createElement: node, createTextNode: text => ({textContent:text})},
        crypto: {randomUUID: () => 'test-session'},
        FormData: class {
        },
        URL,
        URLSearchParams,
        Blob,
        console,
        Date,
        requestAnimationFrame: fn => { frames.push(fn); return frames.length; },
        setInterval: () => 1,
        setTimeout: (fn, ms) => {
            timers.push(fn);
            return timers.length;
        },
        clearTimeout() {
        }
    };
    const context = vm.createContext(env);
    vm.runInContext(markdownLibrary + '\n' + markdownSource, context);
    context.fetch = async (path) => ({
        ok: true,
        status: 200,
        json: async () => ({id: 'test-user', username: 'test_admin', role: 'ADMIN'}),
        text: async () => JSON.stringify(path.endsWith('/status') ? {
            modelConfigured: true,
            embeddingConfigured: false
        } : [])
    });
    return {
        context, nodes, timers, frames, calls, get, flushFrame() { frames.splice(0).forEach(fn => fn()); }, async init() {
            await vm.runInContext(`(async()=>{${source}\n selected='run-1'; currentRun={id:'run-1',status:'RUNNING',answer:'',sessionId:'session-1'}; status={modelConfigured:true}; return {addEvent,resetEvidenceFilter,setEvidenceFilter:(value)=>{evidenceFilter=value;applyEvidenceFilter();},loadUsage,loadUsageSession,loadRun,pollError,schedulePoll,connectStream,closeStream,read:()=>({currentRun,currentEvents,pollFailures,draftAnswer}),invalidate:()=>{pollEpoch++;},setApi:(value)=>{api=value;}};})()`, context).then(api => this.controller = api);
            return this;
        }
    };
}

test('evidence filters retain new events and restore all categories on session reset', async () => {
    const f = await fixture().init();
    const event = (id, tool) => ({id, kind: 'TOOL_RESULT', createdAt: '2026-09-08T02:00:00Z', content: {tool, state: 'success', text: '{}'}});
    f.controller.addEvent(event(1, 'get_effective_config'));
    const configuration = f.get('#evidence').children[0];
    f.controller.setEvidenceFilter('logs');
    assert.equal(configuration.hidden, true);
    assert.equal(f.get('#evidence-filter-empty').hidden, false);
    f.controller.addEvent(event(2, 'get_recent_logs'));
    const logs = f.get('#evidence').children[1];
    assert.equal(logs.hidden, false);
    assert.equal(f.get('#evidence-filter-empty').hidden, true);
    f.controller.addEvent({id: 3, kind: 'KNOWLEDGE', createdAt: '2026-09-08T02:00:00Z', content: {mode: 'LEXICAL', passages: [{id: 'p1', title: '版本说明', version: '2.0', location: '第 1 页', content: '保留来源'}]}});
    const citation = f.get('#evidence').children.at(-1);
    assert.equal(citation.hidden, true);
    f.controller.setEvidenceFilter('documents');
    assert.equal(citation.hidden, false);
    assert.equal(logs.hidden, true);
    f.controller.resetEvidenceFilter();
    assert.equal(configuration.hidden, false);
    assert.equal(logs.hidden, false);
    assert.equal(citation.hidden, false);
    assert.equal(f.get('#evidence').children.length, 4);
});

test('knowledge evidence distinguishes model reranking and preserves historical citations', async () => {
    const f = await fixture().init();
    const passage = {id: 'p1', title: '迁移规则', version: '2.0', location: '第1页', content: '原文'};
    f.controller.addEvent({kind: 'KNOWLEDGE', content: {mode: 'HYBRID', ranking: {method: 'ONNX'}, passages: [{...passage, rerankScore: -2.5}]}});
    const entries = f.get('#evidence').children;
    assert.match(entries[0].textContent, /本地模型重排序/);
    assert.match(entries[1].children.at(-1).textContent, /-2.5000.*非置信度/);
    f.controller.addEvent({kind: 'KNOWLEDGE', content: {mode: 'LEXICAL', passages: [passage]}});
    assert.match(entries[2].textContent, /排名融合/);
    assert.equal(entries[3].children.some(child => child.textContent.includes('模型相关性')), false);
});

test('terminal snapshot reads events after status and retains final evidence', async () => {
    const f = await fixture().init();
    let resolveStatus;
    f.controller.setApi(async (path) => {
        f.calls.push(path);
        if (path === '/runs/run-1') return new Promise(r => resolveStatus = r);
        if (path.includes('/events')) return [{
            id: 9,
            kind: 'ERROR',
            content: {type: 'test'},
            createdAt: new Date().toISOString()
        }];
        return [];
    });
    const pending = f.controller.loadRun('run-1');
    await Promise.resolve();
    assert.deepEqual(f.calls, ['/runs/run-1']);
    resolveStatus({
        id: 'run-1',
        status: 'FAILED',
        question: 'test',
        answer: '',
        errorCode: 'test',
        sessionId: 'session-1',
        createdAt: new Date().toISOString(),
        elapsedMs: 4,
        inputTokens: 0,
        outputTokens: 0
    });
    await pending;
    assert.equal(f.calls[1], '/runs/run-1/events?after=0');
    assert.equal(f.controller.read().currentEvents[0].id, 9);
    assert.equal(f.controller.read().currentRun.status, 'FAILED');
});
test('transient read failure retries same task and reveals recovery after three retries', async () => {
    const f = await fixture().init();
    f.controller.setApi(async (path) => {
        f.calls.push(path);
        throw Error('temporary network failure');
    });
    f.controller.schedulePoll('run-1', 0);
    for (let i = 0; i < 4; i++) {
        const timer = f.timers.shift();
        assert.ok(timer);
        await timer();
    }
    assert.equal(f.calls.length, 4);
    assert.ok(f.calls.every(p => p === '/runs/run-1'));
    assert.equal(f.timers.length, 0);
    assert.equal(f.get('#refresh-run').hidden, false);
    assert.equal(f.controller.read().currentRun.status, 'RUNNING');
});
test('late response from an old selection cannot overwrite the current view', async () => {
    const f = await fixture().init();
    let finish;
    f.controller.setApi(() => new Promise(r => finish = r));
    const pending = f.controller.loadRun('run-1');
    f.controller.invalidate();
    finish({status: 'COMPLETED', answer: 'stale'});
    await pending;
    assert.equal(f.controller.read().currentRun.status, 'RUNNING');
    assert.equal(f.controller.read().currentEvents.length, 0);
});

test('SSE drafts deduplicate events, reset between rounds, and final answer replaces draft', async () => {
    const f = await fixture().init();
    const connections = [];
    f.context.EventSource = class {
        constructor(url) { this.url = url; connections.push(this); }
        addEventListener(name, handler) { this.receive = handler; }
        close() { this.closed = true; }
    };
    f.controller.connectStream('run-1', 0);
    const stream = connections[0];
    const run = {id:'run-1',status:'RUNNING',question:'问题',answer:'',createdAt:new Date().toISOString()};
    const delta = (id, text, reset = false) => ({id,kind:'ANSWER_DELTA',content:{reset,delta:text}});
    const send = (events, snapshot = run) => stream.receive({data:JSON.stringify({run:snapshot,events})});
    send([delta(1,'## 标题'),delta(2,'\n\n**内容**')]);
    assert.equal(f.controller.read().draftAnswer, '## 标题\n\n**内容**');
    send([delta(2,'\n\n**内容**')]);
    assert.equal(f.controller.read().currentEvents.length, 2);
    send([delta(3,'',true),delta(4,'新一轮')]);
    assert.equal(f.controller.read().draftAnswer, '新一轮');
    send([], {...run,status:'COMPLETED',answer:'**最终回答**'});
    assert.equal(stream.closed, true);
    f.flushFrame();
    const finalBody = f.get('#conversation').children[1].children.find(child => child.className === 'message-body markdown-body');
    assert.match(finalBody.innerHTML, /<strong>最终回答<\/strong>/);
});

test('SSE ignores stale selections and reconnects with persisted cursor after failure', async () => {
    const f = await fixture().init();
    const connections = [];
    f.context.EventSource = class {
        constructor(url) { this.url = url; connections.push(this); }
        addEventListener(name, handler) { this.receive = handler; }
        close() { this.closed = true; }
    };
    f.controller.connectStream('run-1', 0);
    connections[0].receive({data:JSON.stringify({run:{id:'run-1',status:'RUNNING'},events:[{id:7,kind:'ANSWER_DELTA',content:{delta:'首段'}}]})});
    connections[0].onerror();
    assert.equal(connections[0].closed, true);
    assert.equal(f.controller.read().draftAnswer, '首段');
    f.controller.connectStream('run-1', 0);
    assert.match(connections[1].url, /after=7$/);
    f.controller.invalidate();
    connections[1].receive({data:JSON.stringify({run:{id:'run-1',status:'COMPLETED',answer:'过期'},events:[]})});
    assert.equal(f.controller.read().currentRun.status, 'RUNNING');
});

test('Markdown supports tables, nested lists, code, quotes, and blocks executable content', async () => {
    const f = await fixture().init();
    const html = f.context.renderMarkdown('## 标题\n\n**加粗** `配置键`\n\n| 事实 | 证据 |\n| --- | --- |\n| 路径 | `/v2` |\n\n> 引用\n\n- 一级\n  - 二级\n\n```java\n<script>alert(1)</script>\n```\n\n<script>alert(2)</script>\n\n[危险](javascript:alert(3))\n\n![图](https://example.com/tracking.png)');
    for (const tag of ['h2','strong','table','blockquote','ul','pre','code']) assert.ok(html.includes('<' + tag));
    assert.ok(html.includes('&lt;script&gt;'));
    assert.doesNotMatch(html, /<script|<img|href="javascript:/);
    assert.match(f.context.renderMarkdown('```\n未完成代码'), /<pre><code>未完成代码/);
});

test('stream rendering coalesces packets into one frame and preserves earlier DOM blocks', async () => {
    const f = await fixture().init();
    let stream;
    f.context.EventSource = class {
        constructor() { stream = this; }
        addEventListener(name, handler) { this.receive = handler; }
        close() {}
    };
    f.controller.connectStream('run-1', 0);
    const run = {id:'run-1',status:'RUNNING',question:'问题',answer:'',createdAt:new Date().toISOString()};
    let id = 0;
    const send = delta => stream.receive({data:JSON.stringify({run,events:[{id:++id,kind:'ANSWER_DELTA',content:{delta}}]})});
    send('## 已成形标题\n\n');
    send('正在');
    send('生成');
    assert.equal(f.frames.length, 1);
    f.flushFrame();
    const conversation = f.get('#conversation');
    const question = conversation.children[0];
    const answer = conversation.children[1];
    const body = answer.children.find(child => child.className === 'message-body markdown-body');
    const heading = body.children[0];
    assert.match(heading.innerHTML, /已成形标题/);
    send('的段落');
    f.flushFrame();
    assert.equal(conversation.children[0], question);
    assert.equal(conversation.children[1], answer);
    assert.equal(body.children[0], heading);
    assert.match(body.children.at(-1).innerHTML, /正在生成的段落/);
});

test('incremental Markdown parses only the tail and finalizes cross-block references', async () => {
    const f = await fixture().init();
    const body = f.context.document.createElement('div');
    const update = f.context.createMarkdownStream(body);
    const parseSizes = [];
    f.context.parseSizes = parseSizes;
    vm.runInContext('const originalParse = markdownRenderer.parse.bind(markdownRenderer); markdownRenderer.parse = (text, env) => { parseSizes.push(text.length); return originalParse(text, env); };', f.context);
    const prefix = '# 标题\n\n' + '已经完成的长段落。'.repeat(100) + '\n\n';
    update(prefix + '末尾');
    const first = body.children[0];
    update(prefix + '末尾追加');
    assert.ok(parseSizes.at(-1) < 20);
    assert.equal(body.children[0], first);
    const source = '[引用][ref]\n\n另一段\n\n[ref]: https://example.com';
    update(source);
    update(source, true);
    assert.equal(body.innerHTML, f.context.renderMarkdown(source));
    assert.match(body.innerHTML, /href="https:\/\/example.com"/);
    update('新一轮');
    assert.match(body.children.at(-1).innerHTML, /新一轮/);
});

// 用量页面的旧请求与失败必须保持可识别，不能显示另一筛选条件的数据。
test('usage analysis rejects stale replies and clears prior results on failure', async () => {
    const f = await fixture().init();
    const responses = [];
    f.controller.setApi(path => new Promise((resolve, reject) => responses.push({path, resolve, reject})));
    f.get('#usage-from').value = '2026-09-01';
    f.get('#usage-to').value = '2026-09-07';
    f.get('#usage-granularity').value = 'day';
    const first = f.controller.loadUsage();
    f.get('#usage-from').value = '2026-09-02';
    const second = f.controller.loadUsage();
    const empty = {from:'2026-09-02',to:'2026-09-07',summary:{runs:0,sessions:0,inputTokens:0,outputTokens:0,completed:0,unreported:0}};
    responses[1].resolve(empty); await second;
    responses[0].resolve({...empty,from:'2026-09-01'}); await first;
    assert.match(f.get('#usage-feedback').textContent, /2026-09-02/);
    const third = f.controller.loadUsage();
    responses[2].reject(new Error('fixture failure')); await third;
    assert.match(f.get('#usage-feedback').textContent, /查询失败/);
    assert.equal(f.get('#usage-results').children.length, 0);
    assert.equal(f.get('#usage-refresh').disabled, false);
});

