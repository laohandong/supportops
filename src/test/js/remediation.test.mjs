import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
const source = await readFile(new URL('../../main/resources/static/remediation.js', import.meta.url),'utf8');
const {createRemediationCommandStore, validateRemediationCommand} = await import('data:text/javascript;base64,'+Buffer.from(source).toString('base64'));
const storage = () => {const values = new Map();return {getItem:key=>values.get(key)||null,setItem:(key,value)=>values.set(key,value),removeItem:key=>values.delete(key)};};
test('frozen method/path/body/request identity survives response loss and refresh',()=>{
    const local=storage(), first=createRemediationCommandStore(local,'admin-a');
    const body={requestId:'fixed-id',expectedVersion:2,planHash:'original-hash',reason:null};
    const frozen=first.freeze('/remediations/plan/execute',body);
    body.expectedVersion=99;
    const refreshed=createRemediationCommandStore(local,'admin-a');
    assert.deepEqual(refreshed.read(),frozen);
    assert.equal(refreshed.read().body.expectedVersion,2);
    assert.throws(()=>{frozen.body.expectedVersion=100;},TypeError);
    assert.throws(()=>refreshed.freeze('/remediations/plan/execute',{requestId:'new-id'}),/待确认/);
    assert.equal(refreshed.confirm(null),false);
    assert.equal(refreshed.confirm({requestId:'different',httpStatus:200}),false);
    assert.deepEqual(refreshed.read(),frozen);
    assert.equal(refreshed.confirm({requestId:'fixed-id',httpStatus:200}),true);
    assert.equal(refreshed.read(),null);
});
test('account changes cannot read or reuse another account pending command',()=>{
    const local=storage(), a=createRemediationCommandStore(local,'admin-a'), b=createRemediationCommandStore(local,'admin-b');
    a.freeze('/remediations/plan/execute',{requestId:'a-fixed'});
    assert.equal(b.read(),null);
    assert.equal(b.confirm({requestId:'a-fixed',httpStatus:200}),false);
    assert.equal(a.read().requestId,'a-fixed');
});
test('only matching durable acceptance or rejection unlocks the original intent',()=>{
    const local=storage(), store=createRemediationCommandStore(local,'admin');
    store.freeze('/remediations/plan/approve',{requestId:'fixed'});
    for(const result of [{httpStatus:404}, {requestId:'fixed'}, undefined]) assert.equal(store.confirm(result),false);
    assert.throws(()=>store.freeze('/remediations/other/execute',{requestId:'replacement'}),/待确认/);
    assert.equal(store.confirm({requestId:'fixed',httpStatus:409,errorCode:'REMEDIATION_PLAN_EXPIRED'}),true);
});

test('blank or invalid inputs are rejected before freezing or sending a write',()=>{
    const local=storage(),store=createRemediationCommandStore(local,'admin');
    const body={requestId:'12345678-1234-1234-1234-123456789012',actionId:'ORDERBRIDGE_MIGRATE_SYNC_PATH',name:'有效提案',reason:'合成验收',sourceRunId:null,supersedesExecutionId:null};
    for(const invalid of [{name:'   '},{reason:'\t\n  '},{name:'x'.repeat(121)},{reason:'x'.repeat(1001)},{sourceRunId:'invalid'},{requestId:'invalid'}]){
        assert.throws(()=>{validateRemediationCommand('/remediations',{...body,...invalid});store.freeze('/remediations',{...body,...invalid});});
        assert.equal(store.read(),null);
    }
    validateRemediationCommand('/remediations',body);
    assert.equal(store.freeze('/remediations',body).requestId,body.requestId);
});

test('invalid historical frozen input survives ordinary 404 and clears only on durable rejection',()=>{
    const local=storage(),old=createRemediationCommandStore(local,'admin');
    const body={requestId:'old-fixed-request',reason:'   '};
    old.freeze('/remediations',body);
    const refreshed=createRemediationCommandStore(local,'admin');
    assert.equal(refreshed.confirm({httpStatus:404}),false);
    assert.deepEqual(refreshed.read().body,body);
    assert.equal(refreshed.confirm({requestId:body.requestId,httpStatus:400,errorCode:'INVALID_INPUT'}),true);
    assert.equal(refreshed.read(),null);
});
