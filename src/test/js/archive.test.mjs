import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';

const source = await readFile(new URL('../../main/resources/static/app.js', import.meta.url), 'utf8');
const historySource = source.slice(source.indexOf('async function history()'), source.indexOf('\nfunction message('));

// 执行正式历史控制器，夹具仅提供 DOM 和可控网络，验证成功、失败与迟到响应。
function fixture(api) {
    const nodes = new Map();
    const notices = [];
    function element(tag, text, className) {
        return {tag, textContent: text, className, children: [], listeners: {}, disabled: false,
            classList: {toggle() {}}, setAttribute() {},
            append(...children) { this.children.push(...children); },
            replaceChildren(...children) { this.children = children; },
            addEventListener(name, callback) { this.listeners[name] = callback; },
            click() { this.clicked = true; }, focus() { this.focused = true; }};
    }
    const context = vm.createContext({api, historyEpoch: 0, sessionId: 'viewing', selected: null,
        statuses: {COMPLETED: '已完成', RUNNING: '诊断中'}, isAdmin: false,
        el: element, $: selector => {
            if (!nodes.has(selector)) nodes.set(selector, element('div'));
            return nodes.get(selector);
        }, notify: text => notices.push(text), openRun: async () => {}});
    vm.runInContext(historySource, context);
    return {context, nodes, notices, history: () => context.history()};
}
const run = (id, sessionId = 'viewing', status = 'COMPLETED') =>
    ({id, sessionId, status, question: '合成诊断', createdAt: '2026-09-08T00:00:00Z'});

test('归档当前对话后刷新列表并新建会话，发送明确 POST', async () => {
    let archived = false;
    const calls = [];
    const f = fixture(async (path, options) => {
        calls.push({path, method: options?.method});
        if (path.endsWith('/archive')) { archived = true; return null; }
        return archived ? [run('other', 'other')] : [run('one'), run('two'), run('other', 'other')];
    });
    await f.history();
    await f.nodes.get('#history').children[0].children[1].listeners.click();
    assert.equal(calls[1].path, '/runs/one/archive');
    assert.equal(calls[1].method, 'POST');
    assert.equal(f.nodes.get('#history').children.length, 1);
    assert.equal(f.nodes.get('#new-session').clicked, true);
    assert.match(f.notices[0], /统计仍保留/);
});

test('归档失败保留记录且允许幂等重试', async () => {
    const f = fixture(async path => {
        if (path.endsWith('/archive')) throw new Error('网络中断');
        return [run('one')];
    });
    await f.history();
    const button = f.nodes.get('#history').children[0].children[1];
    await button.listeners.click();
    assert.equal(button.disabled, false);
    assert.equal(f.nodes.get('#history').children.length, 1);
    assert.deepEqual(f.notices, ['网络中断']);
});

test('旧历史响应不能覆盖归档后的新列表', async () => {
    let resolveOld;
    let calls = 0;
    const f = fixture(() => ++calls === 1 ? new Promise(resolve => resolveOld = resolve) : []);
    const old = f.history();
    await f.history();
    resolveOld([run('old')]);
    await old;
    assert.equal(f.nodes.get('#history').children[0].textContent, '暂无诊断记录');
});

test('同对话任意轮执行中，整段对话归档按钮禁用', async () => {
    const f = fixture(async () => [run('one'), run('two', 'viewing', 'RUNNING'), run('other', 'other')]);
    await f.history();
    assert.deepEqual(f.nodes.get('#history').children.map(row => row.children[1].disabled), [true, true, false]);
});
