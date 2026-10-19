import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {Element, walk} from './fixtures/evaluation-dom.mjs';
const source=await readFile(new URL('../../main/resources/static/remediation.js',import.meta.url),'utf8');
const {initializeRemediation}=await import('data:text/javascript;base64,'+Buffer.from(source).toString('base64'));

/** 只控制读取响应顺序，不替代真实浏览器、后端与持久化验收。 */
function harness(context,{deferHistory=false}={}){
  const previous=globalThis.localStorage;
  globalThis.localStorage={getItem:()=>null};
  const nodes=new Map(['#remediation-content','#remediation-feedback','#remediation-refresh'].map(id=>[id,new Element('div')]));
  const lists=[],histories=[];
  const api=path=>{
    if(path.startsWith('/remediations?'))return new Promise((resolve,reject)=>lists.push({resolve,reject}));
    assert.ok(path.startsWith('/demo/environment-events?'));
    return deferHistory?new Promise(resolve=>histories.push(resolve)):Promise.resolve([]);
  };
  const ui=initializeRemediation({api,el:(tag,text)=>new Element(tag,text),$:id=>nodes.get(id),notify(){},isAdmin:true,userId:'synthetic-render-user'});
  context.after(()=>{ui.setActive(false);if(previous===undefined)delete globalThis.localStorage;else globalThis.localStorage=previous;});
  const result=name=>({total:1,items:[{status:'PENDING_APPROVAL',plan:{name,before:{instanceId:'synthetic-instance'},createdAt:'2026-10-02T00:00:00Z'}}]});
  return {ui,lists,histories,result,root:nodes.get('#remediation-content'),feedback:nodes.get('#remediation-feedback')};
}

test('late list response cannot append a duplicate table or replace the latest selection',async context=>{
  const h=harness(context),first=h.ui.refresh(),second=h.ui.refresh();
  h.lists[1].resolve(h.result('current'));await second;
  h.lists[0].resolve(h.result('obsolete'));await first;
  const nodes=walk(h.root);
  assert.equal(nodes.filter(node=>node.tag==='table').length,1);
  assert.equal(nodes.filter(node=>node.tag==='button'&&node.textContent==='current').length,1);
  assert.equal(nodes.filter(node=>node.textContent==='obsolete').length,0);
});

test('late history response cannot append into a newer page',async context=>{
  const h=harness(context,{deferHistory:true}),first=h.ui.refresh();
  h.lists[0].resolve(h.result('obsolete'));await Promise.resolve();
  assert.equal(h.histories.length,1);
  const second=h.ui.refresh();h.lists[1].resolve(h.result('current'));await Promise.resolve();
  h.histories[1]([]);await second;h.histories[0]([]);await first;
  assert.equal(walk(h.root).filter(node=>node.tag==='h2'&&node.textContent==='场景变更记录').length,1);
  assert.equal(walk(h.root).filter(node=>node.tag==='table').length,1);
});

test('leaving the page invalidates pending reads and late errors do not overwrite feedback',async context=>{
  const h=harness(context),first=h.ui.refresh();h.ui.setActive(false);
  h.lists[0].resolve(h.result('obsolete'));await first;
  assert.equal(walk(h.root).filter(node=>node.tag==='table').length,0);
  const older=h.ui.refresh(),newer=h.ui.refresh();h.lists[2].resolve(h.result('current'));await newer;
  h.lists[1].reject(new Error('obsolete read error'));await older;
  assert.notEqual(h.feedback.textContent,'obsolete read error');
});
