import test from 'node:test';
import assert from 'node:assert/strict';
import {usageSeries, renderUsageDashboard} from '../../main/resources/static/usage-charts.js';

const row = (groupId, inputTokens, outputTokens, runs) => ({groupId, inputTokens, outputTokens, runs, sessions:runs ? 1 : 0, completed:runs, unreported:0, elapsedMs:0, question:'合成验收会话'});
function fixture() {
    const charts = [], opened = [];
    const el = (tag, text, className) => ({tag, textContent:text, className, children:[], style:{}, events:{}, append(...nodes) {this.children.push(...nodes);}, replaceChildren(...nodes) {this.children=nodes;}, setAttribute(key,value){this[key]=value;}, addEventListener(name,callback){this.events[name]=callback;}});
    class Chart { constructor(canvas, config) {this.config=config;this.destroyed=false;charts.push(this);} destroy(){this.destroyed=true;} }
    return {el, charts, opened, Chart, root:el('div'), onSession:(...args)=>opened.push(args)};
}
function data() {
    return {granularity:'hour', summary:row('all',200,50,3), timeline:[row('2026-09-07T00',100,20,2),row('2026-09-07T01',0,0,0),row('2026-09-07T02',100,30,1)], topSessions:[row('a',180,40,2),row('b',20,10,1)]};
}
test('cumulative counts every time bucket and means leave inactive periods empty',()=>{
    const source=data().timeline, result=usageSeries(source);
    assert.deepEqual(result.map(x=>x.cumulative),[120,120,250]);
    assert.deepEqual(result.map(x=>x.average),[60,null,130]);
    assert.equal(source[0].cumulative,undefined);
});
test('four chart datasets preserve totals, rank drilldown and destroy lifecycle',()=>{
    const f=fixture(), previous=globalThis.document;
    globalThis.document={createTextNode:text=>({textContent:text})};
    try {
        const cleanup=renderUsageDashboard(f.root,data(),f);
        assert.equal(f.charts.length,4);
        assert.deepEqual(f.charts.map(c=>c.config.type),['bar','line','bar','line']);
        assert.deepEqual(f.charts[0].config.data.datasets[0].data,[100,0,100]);
        assert.deepEqual(f.charts[1].config.data.datasets[0].data,[120,120,250]);
        assert.deepEqual(f.charts[3].config.data.datasets[0].data,[60,null,130]);
        f.charts[2].config.options.onClick({},[{index:1}]);
        assert.deepEqual(f.opened,[['b',0]]);
        cleanup(); assert.ok(f.charts.every(c=>c.destroyed));
    } finally {globalThis.document=previous;}
});
test('empty query has no fabricated charts',()=>{
    const f=fixture(); renderUsageDashboard(f.root,{summary:row('all',0,0,0),timeline:[],topSessions:[]},f);
    assert.equal(f.charts.length,0);
    assert.ok(f.root.children.some(n=>n.className==='usage-empty'));
});
test('90 days of hourly buckets remain complete without sampling away spikes',()=>{
    const rows=Array.from({length:2160},(_,i)=>row(String(i),i===1033?10000:0,0,1));
    const series=usageSeries(rows);assert.equal(series.length,2160);assert.equal(series[1033].total,10000);assert.equal(series.at(-1).cumulative,10000);
});
