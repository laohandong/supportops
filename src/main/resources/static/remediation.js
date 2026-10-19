const ACTION = 'ORDERBRIDGE_MIGRATE_SYNC_PATH';
const labels = {PENDING_APPROVAL:'待审批', APPROVED:'已批准', EXECUTING:'执行中', FINISHED:'已结束', REJECTED:'已驳回', CANCELLED:'已撤销', EXPIRED:'已过期', STALE:'环境已变化', RUNNING:'处理中', SUCCEEDED:'验证通过', FAILED:'确认未应用', VERIFICATION_FAILED:'已应用，验证失败', UNKNOWN:'结果待核查', CLOSED_UNRESOLVED:'人工结束未决', NOT_STARTED:'尚未开始', NOT_APPLIED:'确认未应用', APPLIED:'确认已应用', NOT_RUN:'未验证', PASSED:'验证通过'};
const friendly = {REMEDIATION_ENVIRONMENT_CHANGED:'现场已变化，旧审批不能继续使用。请重新提案。', REMEDIATION_PLAN_EXPIRED:'提案或批准窗口已过期，请重新提案。', REMEDIATION_UNRESOLVED_BLOCKS_TARGET:'目标有历史未决执行，请先核查或人工结束。', REMEDIATION_RESULT_NOT_PERSISTED:'原操作结果未完成保存，保留请求编号并核查原执行。', REMEDIATION_HANDLER_STILL_ACTIVE:'原处理器仍在途或退出身份未确认，暂不能收尾。', REMEDIATION_REQUEST_CONFLICT:'原请求编号内容冲突，请查询原请求，禁止替换执行。'};
const verificationLabel = status => status === 'FAILED' ? '验证失败' : labels[status];

/** 冻结前校验固定协议；恢复旧记录仍须取得服务端持久化回执。 */
export function validateRemediationCommand(path, body) {
    const uuid = /^[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}$/;
    if (!uuid.test(body?.requestId || '')) throw new Error('请求编号无效，请刷新页面。');
    if (path === '/remediations') {
        if (body.actionId !== ACTION) throw new Error('修复动作无效。');
        for (const [key, label, max] of [['name','提案名称',120],['reason','提案理由',1000]]) {
            if (typeof body[key] !== 'string' || !body[key].trim() || body[key].length > max) throw new Error(`${label}不能为空或仅含空白，且最多 ${max} 字。`);
        }
        for (const key of ['sourceRunId','supersedesExecutionId']) {
            if (body[key] !== null && body[key] !== undefined && !uuid.test(body[key])) throw new Error('关联记录编号无效。');
        }
    } else {
        if (!Number.isInteger(body.expectedVersion) || body.expectedVersion < 1 || !/^[a-f0-9]{64}$/.test(body.planHash || '')) throw new Error('提案版本或摘要无效，请重新读取详情。');
        if (body.reason !== null && body.reason !== undefined && (typeof body.reason !== 'string' || body.reason.length > 1000)) throw new Error('理由最多 1000 字。');
        if (/\/(reject|close-unknown)$/.test(path) && !body.reason?.trim()) throw new Error('请填写理由。');
    }
}

/** 一个账号只允许一个待确认命令；读取失败或404均不能解锁替代命令。 */
export function createRemediationCommandStore(storage, userId) {
    const key = `supportops.remediation.pending.v1.${userId}`;
    const immutable = value => {
        if (value && typeof value === 'object') { for (const child of Object.values(value)) immutable(child); Object.freeze(value); }
        return value;
    };
    const read = () => {
        const value = storage.getItem(key);
        if (!value) return null;
        const parsed = JSON.parse(value);
        if (parsed.userId !== userId || parsed.method !== 'POST' || !parsed.requestId || !parsed.path.startsWith('/remediations')) throw new Error('恢复记录异常，请保留原请求并联系管理员。');
        return immutable(parsed);
    };
    return {
        read,
        freeze(path, body) {
            if (read()) throw new Error('有待确认的原请求，请先查询原请求。');
            const command = {userId, method:'POST', path, body:JSON.parse(JSON.stringify(body)), requestId:body.requestId};
            storage.setItem(key, JSON.stringify(command));
            return read();
        },
        confirm(result) {
            const command = read();
            if (!command || result?.requestId !== command.requestId || !Number.isInteger(result.httpStatus)) return false;
            storage.removeItem(key);
            return true;
        }
    };
}

/** 沿用后台 DOM 文本渲染；审批与执行是不同的人工作业。 */
export function initializeRemediation({api, el, $, notify, isAdmin, userId, storage = globalThis.localStorage}) {
    if (!isAdmin) return {refresh:async()=>{}, setActive:()=>{}, newProposal:async()=>{}};
    const store = createRemediationCommandStore(storage, userId);
    const target = $('#remediation-content');
    const feedback = $('#remediation-feedback');
    let selected = null, active = false, timer = null, page = 0, status = '', creating = null, draft = null, busy = false, renderSequence = 0;
    const currentRender = sequence => sequence === renderSequence;
    const message = value => { feedback.textContent = value; };
    const errorText = error => friendly[error.code] || error.message;
    const node = (tag, text, cls) => el(tag, text, cls);
    const button = (text, work, cls='secondary') => {
        const result = node('button', text, cls);
        result.type = 'button';
        result.addEventListener('click', async () => { if (busy) return; busy=true; result.disabled=true; try { await work(); } catch(error) { message(errorText(error)); } finally { busy=false; result.disabled=false; } });
        return result;
    };
    const line = (parent, title, value) => {
        const row = node('div', undefined, 'rc-fact');
        row.append(node('dt', title), node('dd', value ?? '暂无证据'));
        parent.append(row);
    };
    const focus = heading => { heading.tabIndex=-1; heading.focus({preventScroll:true}); };
    const pending = () => store.read();
    const commandBody = (detail, reason=null) => ({requestId:crypto.randomUUID(), expectedVersion:detail.version, planHash:detail.planHash, reason});
    const recovery = async () => {
        const command = pending();
        if (!command) return;
        try {
            const result = await api(`/remediations/requests/${command.requestId}`);
            if (!store.confirm(result)) return;
            if (result.detail) { selected=result.planId; creating=null; draft=null; }
            message(result.httpStatus >= 400 ? `原请求已确认拒绝：${friendly[result.errorCode] || result.errorCode}。` : '原请求已确认受理。请查看保存的结果。');
            await refresh();
        } catch (error) {
            message('原请求尚未确认。继续保留原编号；读取失败或暂时不存在不会重新执行。');
        } finally {
            clearTimeout(timer);
            if(active&&pending())timer=setTimeout(()=>recovery(),10000);
        }
    };
    const sendFrozen = async command => {
        try { await api(command.path, {method:command.method, body:command.body}); }
        catch (error) { message(errorText(error) + ' 正在查询原请求。'); }
        await recovery();
        if (pending()) await refresh();
    };
    const mutate = async (path, body) => {
        validateRemediationCommand(path, body);
        const command = store.freeze(path, body);
        await sendFrozen(command);
    };
    const pendingPanel = () => {
        const command = pending();
        if (!command) return null;
        const panel = node('section', undefined, 'rc-warning');
        panel.setAttribute('aria-label','原请求恢复');
        panel.append(node('h2','原请求待确认'), node('p','请查询原请求。刷新或读取失败不会新建写入。'), node('p',command.requestId,'rc-id'));
        panel.append(button('查询原请求',recovery), button('原样重发原请求',()=>sendFrozen(pending())));
        return panel;
    };
    const diff = (before, after) => {
        const grid = node('div',undefined,'rc-diff');
        for (const [title,value] of [['迁移前配置',before],['迁移后配置',after]]) {
            const side = node('section');
            side.append(node('h3',title));
            const facts = node('dl');
            for (const key of Object.keys(value).sort()) line(facts,key,value[key]);
            side.append(facts); grid.append(side);
        }
        return grid;
    };
    const form = async sequence => {
        const preview = await api('/remediations/preview',{method:'POST',body:{actionId:ACTION}});
        if (!currentRender(sequence)) return;
        const heading = node('h2','新建修复提案'); target.append(heading);
        const facts=node('dl');
        line(facts,'动作','OrderBridge 2.0 路径配置迁移'); line(facts,'现场是否适用',preview.before.applicable ? '满足固定迁移条件' : '不满足固定迁移条件');
        line(facts,'实例',preview.before.instanceId); line(facts,'环境修订',preview.before.revision); line(facts,'实际生效路径',preview.before.effectivePath);
        target.append(facts,diff(preview.before.provided,preview.after),node('p',preview.impact,'rc-warning'));
        const form=node('form',undefined,'rc-form');
        const fields={};
        for (const [id,label,max] of [['name','提案名称',120],['reason','提案理由',1000]]) {
            const input=node(id==='reason'?'textarea':'input'); input.id=`remediation-${id}`; input.name=id; input.maxLength=max; input.required=true;
            input.value=draft?.[id] || (id==='name'?'OrderBridge 2.0 路径迁移':'');
            const title=node('label',label); title.htmlFor=input.id; form.append(title,input); fields[id]=input;
            input.addEventListener('input',()=>{ draft={name:fields.name.value,reason:fields.reason.value}; });
        }
        if (creating?.sourceRunId) form.append(node('p',`来源诊断：${creating.sourceRunId}`,'rc-id'));
        if (creating?.supersedesExecutionId) form.append(node('p',`替代人工结束的未决执行：${creating.supersedesExecutionId}`,'rc-id'));
        const submit=node('button','创建待审批提案','primary'); submit.type='submit'; submit.disabled=!preview.before.applicable || !!pending();
        form.append(submit,button('返回列表',async()=>{creating=null; draft=null; await refresh();}));
        form.addEventListener('submit',async event=>{
            event.preventDefault(); if (busy || pending()) return; busy=true; submit.disabled=true;
            try { await mutate('/remediations',{requestId:crypto.randomUUID(), actionId:ACTION, name:fields.name.value, reason:fields.reason.value, sourceRunId:creating?.sourceRunId||null, supersedesExecutionId:creating?.supersedesExecutionId||null}); }
            catch(error) {message(errorText(error));} finally {busy=false;submit.disabled=!!pending();}
        });
        target.append(form); focus(heading);
    };
    const showDetail = async (detail, sequence) => {
        if (!currentRender(sequence)) return;
        const heading=node('h2',detail.plan.name); target.append(button('返回列表',async()=>{selected=null;await refresh();}),heading);
        const facts=node('dl');
        line(facts,'提案状态',labels[detail.status]||detail.status); line(facts,'提案人',detail.plan.creatorId); line(facts,'来源诊断',detail.plan.sourceRunId||'独立提案');
        line(facts,'理由',detail.plan.reason); line(facts,'实例',detail.plan.before.instanceId); line(facts,'环境修订',detail.plan.before.revision);
        line(facts,'提案截止',new Date(detail.plan.planExpiresAt).toLocaleString()); line(facts,'审批人',detail.decision?.actorId||'尚未审批');
        if (detail.decision) { line(facts,'审批窗口截止',new Date(detail.decision.expiresAt).toLocaleString()); line(facts,'决定理由',detail.decision.reason||'未填写'); }
        target.append(facts,diff(detail.plan.before.provided,detail.plan.after),node('p',detail.plan.impact,'rc-warning'));
        if(detail.blockedReason) target.append(node('p',friendly[detail.blockedReason]||detail.blockedReason,'rc-warning'));
        const reason=node('textarea'); reason.id='remediation-decision-reason'; reason.maxLength=1000; reason.rows=2;
        const reasonLabel=node('label','审批、驳回或人工结束理由'); reasonLabel.htmlFor=reason.id; target.append(reasonLabel,reason);
        const actions=node('div',undefined,'rc-actions');
        const addCommand=(label,path,enabled,requiredReason=false)=>{
            const control=button(label,async()=>{ if(requiredReason&&!reason.value.trim()) throw new Error('请填写理由。'); await mutate(path,commandBody(detail,reason.value||null)); });
            control.disabled=!enabled||!!pending(); actions.append(control);
        };
        addCommand('批准修复',`/remediations/${detail.id}/approve`,detail.canApprove);
        addCommand('驳回提案',`/remediations/${detail.id}/reject`,detail.status==='PENDING_APPROVAL',true);
        addCommand('撤销提案',`/remediations/${detail.id}/cancel`,detail.canCancel);
        addCommand('执行已批准修复',`/remediations/${detail.id}/execute`,detail.canExecute);
        if(detail.execution) {
            addCommand('核查原执行',`/remediations/executions/${detail.execution.id}/reconcile`,detail.canReconcile);
            addCommand('人工结束未决记录',`/remediations/executions/${detail.execution.id}/close-unknown`,detail.canCloseUnknown,true);
        }
        actions.append(button('重新采集并创建新提案',async()=>{creating={sourceRunId:detail.plan.sourceRunId,supersedesExecutionId:detail.execution?.status==='CLOSED_UNRESOLVED'?detail.execution.id:null}; selected=null; draft={name:detail.plan.name,reason:detail.plan.reason}; await refresh();}));
        for(const format of ['json','markdown']) {const link=node('a',`导出 ${format==='json'?'JSON':'Markdown'}`);link.href=`/api/remediations/${detail.id}/report?format=${format}`;link.download=`remediation-${detail.id}.${format==='json'?'json':'md'}`;actions.append(link);}
        target.append(actions);
        if(detail.execution) {
            const execution=detail.execution, receipt=execution.receipt;
            target.append(node('h2','执行结果'),node('p',labels[execution.status]||execution.status,execution.status==='SUCCEEDED'?'rc-result rc-success':'rc-result'));
            const evidence=node('div',undefined,'rc-diff');
            for(const [title,rows] of [['配置应用',[['应用事实',labels[execution.applicationStatus]],['操作编号',execution.operationId],['应用时间',receipt?.application?.appliedAt],['应用后修订',receipt?.application?.afterRevision]]],['执行后验证',[['验证事实',verificationLabel(execution.verificationStatus)],['新请求编号',execution.verificationRequestId],['路径',receipt?.verification?.path],['HTTP状态',receipt?.verification?.httpStatus],['accepted',receipt?.verification?.accepted],['验证时间',receipt?.verification?.finishedAt]]]]) {
                const side=node('section'); side.append(node('h3',title)); const facts=node('dl');for(const [key,value] of rows)line(facts,key,value);side.append(facts);evidence.append(side);
            }
            target.append(evidence,node('p',execution.errorCode||'原证据已保存。','rc-warning'));
            if(['UNKNOWN','RUNNING','CLOSED_UNRESOLVED'].includes(execution.status)) target.append(node('p','缺少原证据时不能根据当前配置推断本次成功。人工结束保留不明事实。','rc-warning'));
        }
        target.append(node('h2','审批与执行审计'));
        const timeline=node('ol',undefined,'rc-timeline');
        let cursor=0;
        const loadAudit=async()=>{
            more.disabled=true;
            try {
                const events=await api(`/remediations/${detail.id}/events?after=${cursor}`);
                if (!currentRender(sequence)) return;
                for(const event of events) { const row=node('li');row.append(node('strong',event.kind),node('p',`${new Date(event.createdAt).toLocaleString()} · ${event.actorId||'启动恢复'}`));const raw=node('details');raw.append(node('summary','查看原审计证据'),node('pre',event.payloadJson,'rc-raw'));row.append(raw);timeline.append(row);cursor=event.id; }
                more.hidden=events.length<200;
            } finally {more.disabled=false;}
        };
        const more=button('加载更多审计',loadAudit);
        await loadAudit();
        if (!currentRender(sequence)) return;
        target.append(timeline,more);focus(heading);
    };
    const showList = async sequence => {
        const controls=node('div',undefined,'rc-actions'); controls.append(button('新建修复提案',()=>newProposal()));
        const filter=node('select');filter.setAttribute('aria-label','筛选提案状态');
        for(const [value,label] of [['','全部状态'],...Object.entries(labels).filter(([key])=>['PENDING_APPROVAL','APPROVED','EXECUTING','FINISHED','REJECTED','CANCELLED','EXPIRED','STALE'].includes(key))]) {const option=node('option',label);option.value=value;filter.append(option);}
        filter.value=status;filter.addEventListener('change',async()=>{status=filter.value;page=0;await refresh();});controls.append(filter);target.append(controls);
        const result=await api(`/remediations?page=${page}&size=20${status?'&status='+status:''}`);
        if (!currentRender(sequence)) return;
        if(!result.items.length) target.append(node('p','暂无提案。从迁移故障现场创建提案，审阅差异后显式审批。','muted'));
        else {const table=node('table'),head=node('tr');for(const label of ['提案 / 目标实例','状态','审批人','应用 / 验证','创建时间'])head.append(node('th',label));const thead=node('thead');thead.append(head);table.append(thead);const body=node('tbody');
            for(const detail of result.items) {const row=node('tr');const title=node('td');title.append(button(detail.plan.name,async()=>{selected=detail.id;await refresh();}),node('p',detail.plan.before.instanceId.slice(0,8),'muted'));row.append(title,node('td',labels[detail.status]),node('td',detail.decision?.actorId||'尚未审批'),node('td',detail.execution?`${labels[detail.execution.applicationStatus]} / ${verificationLabel(detail.execution.verificationStatus)}`:'尚未执行'),node('td',new Date(detail.plan.createdAt).toLocaleString()));body.append(row);}table.append(body);const wrap=node('div',undefined,'rc-table');wrap.append(table);target.append(wrap);
        }
        const previous=button('上一页',async()=>{page--;await refresh();});previous.disabled=page===0;
        const next=button('下一页',async()=>{page++;await refresh();});next.disabled=(page+1)*20>=result.total;
        const pages=node('div',undefined,'rc-actions');pages.append(previous,node('span',`第 ${page+1} 页 · ${result.total} 条`),next);target.append(pages);
        const history=node('section');history.append(node('h2','场景变更记录'),node('p','人工切换与同名重置均保存原实例、修订和操作者。重启后可审阅历史；记录不证明当前修复成功。','muted'));
        const timeline=node('ol',undefined,'rc-timeline');let cursor=0;
        const load=async()=>{
            const events=await api(`/demo/environment-events?after=${cursor}`);
            if (!currentRender(sequence)) return;
            for(const event of events){const row=node('li');row.append(node('strong',`${event.scenarioBefore} → ${event.scenarioAfter} · ${event.status==='APPLIED'?'确认应用':'结果待核查'}`),node('p',`${event.actorId} · ${new Date(event.startedAt).toLocaleString()} · 修订 ${event.beforeRevision} → ${event.afterRevision??'未知'} · 实例 ${event.instanceId}`));const raw=node('details');raw.append(node('summary','查看场景变更原证据'),node('pre',JSON.stringify(event,null,2),'rc-raw'));row.append(raw);timeline.append(row);cursor=event.id;}
            more.hidden=events.length<200;
            if(!events.length&&!cursor)timeline.append(node('li','暂无已保存的人工场景变更。'));
        };
        const more=button('加载更多场景记录',load);await load();
        if (!currentRender(sequence)) return;
        history.append(timeline,more);target.append(history);
    };
    async function refresh() {
        // 刷新、初始化和导航可能并发；只有最新一轮读取可以继续追加页面或恢复计时器。
        const sequence = ++renderSequence;
        target.replaceChildren();const panel=pendingPanel();if(panel)target.append(panel);
        try {if(creating)await form(sequence);else if(selected)await showDetail(await api(`/remediations/${selected}`),sequence);else await showList(sequence);}
        catch(error){if(currentRender(sequence))message(errorText(error));}
        if (!currentRender(sequence)) return;
        clearTimeout(timer);
        if(active&&pending())timer=setTimeout(()=>recovery(),10000);
    }
    async function newProposal(sourceRunId=null) {creating={sourceRunId};selected=null;draft=null;await refresh();}
    $('#remediation-refresh').addEventListener('click',()=>refresh());
    return {refresh,newProposal,setActive(value){active=value;if(!active)renderSequence++;clearTimeout(timer);if(active&&pending())timer=setTimeout(()=>recovery(),1000);}};
}
