const names = {RESOLVED: '已解决', PARTIALLY_RESOLVED: '部分解决', UNRESOLVED: '未解决', UNCONFIRMED: '无法确认'};

/** 当前轮次处理结果；不参与模型上下文，切换记录和账号丢弃迟到响应。 */
export function initializeOutcome({api, el, $, commands, isAdmin}) {
    const root = $('#outcome-content'), feedback = $('#outcome-feedback');
    let selected = null, detail = null, epoch = 0, runState = null;
    const edits = new Map();
    const say = text => { feedback.textContent = text; };
    const button = (text, action) => {
        const node = el('button', text, 'secondary'); node.type = 'button';
        node.addEventListener('click', async () => {
            if (node.disabled) return; node.disabled = true;
            const sequence = epoch, id = selected;
            try { await action(); } catch (error) { if (sequence === epoch && id === selected) say(error.message); } finally { if (node.isConnected) node.disabled = false; }
        }); return node;
    };
    const field = (form, name, control) => { control.id ||= 'oc-' + crypto.randomUUID(); const label = el('label', name); label.htmlFor = control.id; form.append(label, control); return control; };
    const pending = () => {
        const command = commands.store.read();
        if (command) {
            root.append(el('p', '原请求待确认：' + command.requestId + '。继续保留原方法、正文及编号。', 'oc-warning'),
                button('查询原请求', async () => { const receipt = await commands.recover(); await load(); if (receipt.sourcePackage) download(receipt.sourcePackage); }),
                button('显式重试原命令', async () => { const receipt = await commands.retry(); await load(); if (receipt.sourcePackage) download(receipt.sourcePackage); }));
        }
        if (commands.store.limited) root.append(el('p', '浏览器存储不可用，当前页面保留原请求；关闭或刷新后恢复信息可能丢失。请先确认原请求。', 'oc-warning'));
        return !!command;
    };
    function render() {
        root.replaceChildren();
        const protectedCommand = pending();
        if (!selected) { root.append(el('p', '选择一个诊断轮次后回填处理结果。', 'muted')); return; }
        root.append(el('p', detail?.outcome ? `${names[detail.outcome.status]} · 人员陈述 · 修订 ${detail.version} · ${detail.outcome.confirmedAt}` : '尚未回填处理结果', 'muted'));
        root.append(el('p', '处理事实、修复执行和模型质量分别记录。已解决表示人员判断。', 'muted'));
        if (detail?.outcome) {
            const facts = el('dl', undefined, 'oc-facts');
            for (const [label, value] of [['确认人', detail.outcome.actorId], ['处理措施', detail.outcome.actionsTaken], ['验证说明', detail.outcome.verificationSummary], ['剩余问题', detail.outcome.remainingIssue]]) facts.append(el('dt', label), el('dd', value || '未填写'));
            root.append(facts);
        }
        root.append(button('查看结果修订历史', async () => {
            const sequence = epoch, id = selected, history = await api(`/runs/${id}/outcome/history?size=100`);
            if (sequence !== epoch || id !== selected) return;
            const list = el('div');
            history.items.forEach(item => { const row = el('details'); row.append(el('summary', `修订 ${item.version} · ${names[item.status]} · ${item.confirmedAt}`), el('pre', JSON.stringify(item, null, 2), 'oc-raw')); list.append(row); });
            root.querySelector('[data-outcome-history]')?.remove(); list.dataset.outcomeHistory = 'true'; root.append(list);
        }));
        if (!detail?.canSave) { root.append(el('p', '诊断执行中，终态后可回填。', 'muted')); return; }
        if (protectedCommand) return;
        const id = selected, version = detail.version, form = el('form', undefined, 'form-panel oc-form');
        const edit = edits.get(id) || detail.outcome || {};
        const status = field(form, '处理结果', el('select'));
        Object.entries(names).forEach(([value, text]) => { const option = el('option', text); option.value = value; status.append(option); }); status.value = edit.status || 'UNCONFIRMED';
        const controls = {};
        for (const [key, label, max] of [['actionsTaken', '处理措施或未处理原因', 4000], ['verificationSummary', '验证说明或缺少的验证信息', 4000], ['remainingIssue', '剩余问题', 2000], ['changeReason', '本次变更理由', 1000], ['verificationAction', '处理后的人工验证动作', 2000], ['observation', '处理后的人工观察结果', 2000]]) {
            const input = field(form, label, el('textarea')); input.rows = 2; input.maxLength = max; input.value = edit[key] || ''; controls[key] = input;
        }
        const observedAt = field(form, '人工观察时间（UTC ISO 8601）', el('input')); observedAt.value = edit.observedAt || new Date().toISOString(); observedAt.maxLength = 40;
        const eventId = field(form, '补充诊断事件编号（可选）', el('input')); eventId.type = 'number'; eventId.min = 1; eventId.value = edit.eventId || '';
        const remediation = isAdmin ? field(form, '关联修复证据（管理员可选）', el('select')) : null;
        if (remediation) {
            const empty = el('option', '不关联修复'); empty.value = ''; remediation.append(empty);
            const sequence = epoch;
            api(`/runs/${id}/outcome/remediation-options?size=100`).then(options => {
                if (sequence !== epoch || selected !== id) return;
                options.forEach(item => { const option = el('option', item.plan.name + ' · 应用 ' + (item.execution?.applicationStatus || 'NOT_STARTED') + ' · 验证 ' + (item.execution?.verificationStatus || 'NOT_RUN')); option.value = item.id; remediation.append(option); });
                remediation.value = edit.remediationId || '';
            }).catch(() => {});
        }
        const capture = () => ({status: status.value, ...Object.fromEntries(Object.entries(controls).map(([key, node]) => [key, node.value])), observedAt: observedAt.value,
            eventId:eventId.value, remediationId:remediation?.value || ''});
        form.addEventListener('input', () => edits.set(id, capture()));
        const save = el('button', '确认并保存处理结果', 'primary'); save.type = 'submit'; form.append(save);
        form.addEventListener('submit', async event => {
            event.preventDefault(); if (selected !== id || epoch !== generation) return;
            const data = capture(); edits.set(id, data);
            const evidence = [];
            if (data.verificationAction || data.observation) evidence.push({type:'MANUAL_OBSERVATION', verificationAction:data.verificationAction, observation:data.observation, observedAt:data.observedAt, summary:'人员处理后观察'});
            if (eventId.value) evidence.push({type:'RUN_EVENT', eventId:Number(eventId.value), summary:'来源诊断补充证据'});
            if (remediation?.value) evidence.push({type:'REMEDIATION_VERIFICATION', remediationId:remediation.value, summary:'人员确认引用关联修复'});
            const body = {requestId:crypto.randomUUID(), expectedVersion:version, status:data.status, actionsTaken:data.actionsTaken,
                verificationSummary:data.verificationSummary, remainingIssue:data.remainingIssue, changeReason:data.changeReason, evidence};
            save.disabled = true;
            const inputs = [...form.querySelectorAll('input, select, textarea')]; inputs.forEach(node => { node.disabled = true; });
            try {
                const receipt = await commands.mutate(`/runs/${id}/outcome`, 'SAVE', id, body);
                if (selected !== id || generation !== epoch) return;
                if (receipt.httpStatus < 400) { edits.delete(id); say('处理结果已确认并保存。'); }
                else say('原请求已确认拒绝：' + receipt.errorCode + '。编辑内容保留，请重新读取版本后核对。');
                await load();
            } catch (error) { if (selected === id && generation === epoch) { say(error.message); render(); } }
            finally { if (save.isConnected) { save.disabled = false; inputs.forEach(node => { node.disabled = false; }); } }
        });
        const generation = epoch;
        root.append(form);
        if (isAdmin && detail.outcome) root.append(button('导出脱敏评测来源', () => exportSource(id, detail.outcome.id, generation)));
    }
    function download(pack) {
        const url = URL.createObjectURL(new Blob([JSON.stringify(pack, null, 2)], {type:'application/json'}));
        const link = el('a', '下载来源 JSON'); link.href = url; link.download = 'supportops-source-' + pack.packageId + '.json';
        root.append(link); link.click(); setTimeout(() => URL.revokeObjectURL(url), 60000);
    }
    function exportSource(runId, revisionId, generation) {
        const form = el('form', undefined, 'form-panel oc-form');
        form.append(el('h3', '预览并确认脱敏来源'), el('p', '仅导出下面的问题、摘要和来源修订身份。请先移除账号、地址、客户名称和私密信息。'));
        const question = field(form, '脱敏问题（必填）', el('textarea')); question.maxLength = 6000; question.required = true;
        const summary = field(form, '脱敏结果摘要', el('textarea')); summary.maxLength = 1000;
        const preview = el('pre', '', 'oc-raw'); form.append(preview);
        const confirmed = field(form, '我已核对预览并确认内容可以用于评测', el('input')); confirmed.type = 'checkbox'; confirmed.required = true;
        const refresh = () => { confirmed.checked = false; preview.textContent = JSON.stringify({redactedQuestion:question.value, redactedSummary:summary.value},null,2); };
        question.addEventListener('input',refresh); summary.addEventListener('input',refresh); refresh();
        const submit = el('button','确认并下载来源包','primary'); submit.type='submit'; form.append(submit);
        form.addEventListener('submit',async event => {
            event.preventDefault(); if (generation !== epoch || selected !== runId || !confirmed.checked) return;
            submit.disabled=true;
            try {
                const result=await commands.mutate('/runs/'+runId+'/evaluation-exports','EVALUATION_EXPORT',runId,
                    {requestId:crypto.randomUUID(),expectedOutcomeRevisionId:revisionId,redactedQuestion:question.value,redactedSummary:summary.value,redactionConfirmed:true});
                if (generation !== epoch || selected !== runId) return;
                if (result.httpStatus<400) { download(result.sourcePackage); say('来源包已保存。可在 AgentEval 来源页面导入。'); }
                else say('导出未受理：'+result.errorCode+'。请重新读取来源修订。');
            } catch(error) { say(error.message); render(); }
            finally { if(submit.isConnected) submit.disabled=false; }
        });
        root.append(form); question.focus();
    }
    async function load() {
        if (!selected) { render(); return; }
        const sequence = ++epoch, id = selected;
        try {
            const value = await api(`/runs/${id}/outcome`);
            if (sequence !== epoch || selected !== id) return;
            detail = value; render();
        } catch (error) { if (sequence === epoch && selected === id) say(error.message); }
    }
    return {
        select(run) {
            if (selected === run?.id && runState === run?.status) return;
            selected = run?.id || null; runState = run?.status || null; detail = null; ++epoch;
            root.replaceChildren(el('p', selected ? '正在读取处理结果…' : '选择诊断后回填处理结果。'));
            load();
        }, refresh: load,
        reset() { selected = null; runState = null; detail = null; ++epoch; render(); },
        logout() { ++epoch; edits.clear(); root.replaceChildren(); say(''); }
    };
}
