// 在初始化业务界面前确认身份，防止管理菜单闪现和匿名读取历史。
const identityResponse = await fetch('/api/auth/me');
if (identityResponse.status === 401) {
    location.replace('/login.html');
    await new Promise(() => {});
}
if (!identityResponse.ok) {
    location.replace('/login.html');
    await new Promise(() => {});
}
const signedInUser = await identityResponse.json();
const isAdmin = signedInUser.role === 'ADMIN';
if (!isAdmin) document.querySelector('#first-use-note').textContent = '资料与对话模型由管理员维护，尚未就绪时请联系管理员。';
const userNames = new Map();
userNames.set(signedInUser.id, signedInUser.username);
const ownerLabel = id => id ? (userNames.get(id) || id) : '历史未归属';
document.body.classList.remove('auth-pending');
document.querySelector('.admin-navigation').hidden = !isAdmin;
document.querySelectorAll('[data-page]:not([data-page="diagnosis"])').forEach(button => button.hidden = !isAdmin);
document.querySelector('#current-user').textContent = signedInUser.username + ' · ' + (isAdmin ? '管理员' : '普通用户');

import {renderUsageDashboard} from './usage-charts.js';
import {initializeKnowledge} from './knowledge.js';
let usageEpoch = 0, usageDetailEpoch = 0, historyEpoch = 0;
let destroyUsageCharts = () => {};
let usageReturnFocus = null;
const tokenNumber = value => Number(value).toLocaleString('zh-CN');
const $ = (selector) => document.querySelector(selector);
const errors = {
    USERNAME_EXISTS: '用户名已存在，请换一个用户名。',
    ADMIN_REQUIRED: '此功能仅管理员可以使用。',
    AUTHENTICATION_REQUIRED: '登录已失效，请重新登录。',
    INVALID_USERNAME: '用户名需为 3–32 位字母、数字或下划线。',
    INVALID_PASSWORD: '密码需为 12–128 个字符。',
    MODEL_CATALOG_UNAUTHORIZED: '获取失败：密钥无效或没有查看模型目录的权限。请检查密钥。',
    MODEL_CATALOG_UNSUPPORTED: '该接口不提供模型目录，请按服务商文档手动填写模型 ID。',
    MODEL_CATALOG_RATE_LIMITED: '请求过于频繁，请稍后重试。',
    MODEL_CATALOG_INVALID_RESPONSE: '服务商返回的模型目录格式无效，请检查接口地址或手动填写模型 ID。',
    MODEL_CATALOG_FAILED: '无法获取模型目录，请检查接口地址和服务可用性后重试。',
    MODEL_CATALOG_TIMEOUT: '获取模型目录超时，请稍后重试。',
    MODEL_NOT_CONFIGURED: '对话模型尚未配置。设置环境变量后重启应用即可开始诊断。',
    EMBEDDING_NOT_CONFIGURED: '向量服务尚未配置。可以继续使用关键词检索。',
    RERANK_MODEL_MISSING: '本地重排序模型或分词文件缺失，请联系管理员检查配置。',
    RERANK_MODEL_INVALID: '本地重排序模型无法加载，请检查模型与分词文件是否配套。',
    RERANK_TIMED_OUT: '本地重排序超时，本次未返回检索结果。',
    RERANK_CANCELLED: '本地重排序已取消。',
    RERANK_INFERENCE_FAILED: '本地重排序推理失败，本次未返回检索结果。',
    RERANK_OUTPUT_INVALID: '本地重排序结果无效，本次未返回检索结果。',
    RERANK_UNAVAILABLE: '本地重排序服务不可用，请联系管理员。',
    DIAGNOSIS_IN_PROGRESS: '当前诊断尚未结束，请完成或取消后再操作。',
    SESSION_ARCHIVED: '此对话已归档，请新建会话继续诊断。',
    DOCUMENT_PARSE_FAILED: '无法解析该文档，请检查文件格式及内容。',
    NO_EXTRACTABLE_TEXT: '文档中没有可提取的文字，请上传文字型 PDF 或 Markdown。',
    DIAGNOSIS_EXECUTION_FAILED: '诊断执行失败，请检查模型接口配置或服务可用性，然后重新发起诊断。',
    TIME_BUDGET_EXCEEDED: '诊断已达到时间上限，已停止执行。',
    STEP_LIMIT_REACHED: '已达到诊断步数上限，结果可能尚不完整。',
    CANCELLED_BY_USER: '诊断已取消。',
    PROCESS_RESTARTED: '应用曾重启，该任务已中断。',
    SERVER_SHUTDOWN: '应用关闭时任务已中断。',
    UPLOAD_TOO_LARGE: '文件过大，请上传不超过 20 MB 的文档。',
    INTERNAL_ERROR: '操作未完成，请检查服务配置后重试。'
};
const statuses = {
    QUEUED: '等待执行',
    RUNNING: '诊断中',
    COMPLETED: '已完成',
    FAILED: '失败',
    CANCELLED: '已取消',
    TIMED_OUT: '超时',
    INTERRUPTED: '已中断',
    LIMIT_REACHED: '达到步数上限'
};
const toolNames = {
    get_app_info: '应用信息',
    get_effective_config: '生效配置',
    get_recent_logs: '最近调用日志',
    get_downstream_health: '下游健康状态',
    search_knowledge: '知识检索',
    list_excel_datasets: 'Excel 数据目录',
    query_excel: 'Excel 数据查询',
    load_skill_through_path: '加载排障方法'
};
let selected = null, sessionId = crypto.randomUUID(), currentRun = null, currentEvents = [], status = null,
    pollTimer = null, pollEpoch = 0, pollFailures = 0, runStream = null, draftAnswer = '';
const {documents, showDocument, originalLink, sqlEvidence} = initializeKnowledge({api, el, ownerLabel, canManage: isAdmin, errors, notify, action, $, getStatus: () => status});

function el(tag, text, className) {
    const node = document.createElement(tag);
    if (text !== undefined) node.textContent = text;
    if (className) node.className = className;
    return node;
}

function notify(text, success = false) {
    const n = $('#notice');
    n.textContent = text;
    n.classList.toggle('success', success);
    n.hidden = false;
}

function clearNotice() {
    $('#notice').hidden = true;
}

async function api(path, options = {}) {
    if (options.body && !(options.body instanceof FormData)) {
        options.headers = {...options.headers, 'Content-Type': 'application/json'};
        options.body = JSON.stringify(options.body);
    }
    options.headers = {...options.headers, 'X-SupportOps-Request': '1'};
    const response = await fetch('/api' + path, options);
    if (response.status === 401) { location.replace('/login.html'); }
    const text = await response.text();
    let data;
    try {
        data = text ? JSON.parse(text) : null;
    } catch {
        data = null;
    }
    if (!response.ok) {
        const error = new Error(errors[data?.error] || `操作失败（${data?.error || response.status}），请检查输入或服务状态。`);
        error.code = data?.error;
        throw error;
    }
    return data;
}

async function action(button, job) {
    clearNotice();
    const previous = button.textContent;
    button.disabled = true;
    button.textContent = '处理中…';
    try {
        return await job();
    } catch (e) {
        notify(e.message);
    } finally {
        button.disabled = false;
        button.textContent = previous;
        updateButtons();
    }
}

function page(name) {
    if (!isAdmin && name !== 'diagnosis') return;
    document.querySelectorAll('.page').forEach(p => p.hidden = p.id !== `page-${name}`);
    document.querySelectorAll('.nav-item[data-page]').forEach(b => {
        b.classList.toggle('active', b.dataset.page === name);
        if (b.dataset.page === name) b.setAttribute('aria-current', 'page'); else b.removeAttribute('aria-current');
    });
    // 菜单与服务端角色权限一致，切换页面保留已加载的会话和表单。
    const customerWorkspace = name === 'diagnosis';
    $('#workspace-context').textContent = customerWorkspace ? '客户工作区' : '后台管理';
    $('#workspace-purpose').textContent = customerWorkspace ? '只读诊断 · 人工处理' : '资料维护 · 配置管理';
    clearNotice();
}

document.querySelectorAll('[data-page]').forEach(b => b.addEventListener('click', () => {
    page(b.dataset.page);
    refreshPage(b.dataset.page).catch(e => notify(e.message));
}));

async function refreshPage(name) {
    if (!isAdmin && name !== 'diagnosis') return;
    if (name === 'users') await loadUsers();
    if (name === 'usage') await loadUsage();
    if (name === 'knowledge') await documents();
    if (name === 'memory') await memoryList();
    if (name === 'demo') await demo();
    if (name === 'models' && !modelSettings) await loadModelSettings();
}

function updateButtons() {
    const busy = currentRun && ['QUEUED', 'RUNNING'].includes(currentRun.status);
    const foreignSession = currentRun && currentRun.userId !== signedInUser.id;
    $('#session-ownership-note').hidden = !foreignSession;
    $('#start-run').disabled = !status?.modelConfigured || !!busy || !!foreignSession;
    $('#cancel-run').hidden = !busy;
    $('#export-run').disabled = !currentRun || !!busy;
}

async function history() {
    const epoch = ++historyEpoch;
    const items = await api('/runs');
    if (epoch !== historyEpoch) return;
    const target = $('#history');
    target.replaceChildren();
    if (!items.length) {
        target.append(el('p', '暂无诊断记录', 'muted'));
        return;
    }
    for (const item of items) {
        const b = el('button', undefined, 'history-item');
        b.classList.toggle('selected', item.id === selected);
        b.append(el('span', item.question, 'history-question'), el('small', `${statuses[item.status]} · ${new Date(item.createdAt).toLocaleDateString('zh-CN')}${isAdmin ? ' · ' + ownerLabel(item.userId) : ''}`));
        b.addEventListener('click', () => openRun(item.id).catch(e => notify(e.message)));
        const row = el('div', undefined, 'history-row');
        const archive = el('button', '归档', 'text-button history-archive');
        archive.type = 'button';
        archive.title = '归档整个对话，保留历史统计';
        archive.setAttribute('aria-label', '归档对话：' + item.question);
        archive.disabled = items.some(run => run.sessionId === item.sessionId && ['QUEUED', 'RUNNING'].includes(run.status));
        archive.addEventListener('click', async () => {
            archive.disabled = true;
            archive.textContent = '归档中';
            try {
                await api('/runs/' + encodeURIComponent(item.id) + '/archive', {method: 'POST'});
                ++historyEpoch;
                if (sessionId === item.sessionId) $('#new-session').click();
                await history();
                $('#new-session').focus();
                notify('对话已归档，历史统计仍保留。', true);
            } catch (error) {
                archive.disabled = false;
                archive.textContent = '归档';
                notify(error.message);
            }
        });
        row.append(b, archive);
        target.append(row);
    }
}

function message(role, text, css = '') {
    const item = el('div', undefined, `message ${css}`);
    const body = el('div', text, 'message-body');
    if (css !== 'user-message') {
        body.className += ' markdown-body';
        body.innerHTML = globalThis.renderMarkdown(text);
    }
    const avatar = el('span', css === 'user-message' ? '我' : 'S', 'message-avatar');
    avatar.setAttribute('aria-hidden', 'true');
    item.append(avatar, el('div', role, 'message-role'), body);
    return item;
}

let conversationView = null, renderFrame = null;

// 多个网络片段共用一次绘制；回调仅读取当前任务，切换后不回放旧快照。
function scheduleRunRender() {
    if (renderFrame !== null) return;
    renderFrame = requestAnimationFrame(() => {
        renderFrame = null;
        renderRun();
    });
}

function renderRun() {
    const r = currentRun;
    if (!r) return;
    const conversation = $('#conversation');
    const scrollTop = conversation.scrollTop;
    const follow = conversation.scrollHeight - conversation.clientHeight - scrollTop < 64;
    if (!conversationView || conversationView.id !== r.id) {
        const answer = el('div', undefined, 'message');
        const role = el('div', undefined, 'message-role');
        const body = el('div', undefined, 'message-body markdown-body');
        const pending = el('p', undefined, 'pending-message');
        const avatar = el('span', 'S', 'message-avatar');
        avatar.setAttribute('aria-hidden', 'true');
        answer.append(avatar, role, body);
        conversation.replaceChildren(message('问题描述', r.question, 'user-message'), answer, pending);
        conversationView = {id: r.id, answer, role, pending, update: globalThis.createMarkdownStream(body)};
    }
    const active = ['RUNNING', 'QUEUED'].includes(r.status);
    const text = r.answer || draftAnswer;
    conversationView.answer.hidden = !text;
    conversationView.role.textContent = r.answer ? '诊断建议' : active ? '诊断建议 · 正在生成' : '未完成的回答';
    conversationView.update(text, !active);
    conversationView.pending.hidden = !active && !r.errorCode;
    conversationView.pending.className = active ? 'pending-message busy' : 'pending-message';
    conversationView.pending.textContent = active ? '正在收集证据与核查资料。执行记录会持续更新。' : errors[r.errorCode] || r.errorCode || '';
    const badge = $('#run-state');
    badge.textContent = statuses[r.status] || r.status;
    badge.className = 'badge' + (['FAILED', 'TIMED_OUT'].includes(r.status) ? ' error' : (['RUNNING', 'QUEUED'].includes(r.status) ? ' pending' : ''));
    const metrics = $('#run-metrics');
    metrics.hidden = false;
    metrics.textContent = metricsText(r);
    $('#diagnosis-title').textContent = r.question;
    updateButtons();
    conversation.scrollTop = follow ? conversation.scrollHeight : scrollTop;
}

function citation(p) {
    const node = el('div', undefined, 'citation');
    node.dataset.evidenceCategory = 'documents';
    const button = el(isAdmin ? 'button' : 'strong', p.title, 'citation-title');
    if (isAdmin) button.addEventListener('click', () => showDocument(p.documentId, p.batchId).catch(e => notify(e.message)));
    const excerpt = el('div', undefined, 'markdown-body');
    excerpt.innerHTML = globalThis.renderMarkdown(p.content);
    node.append(button, el('p', `版本 ${p.version} · ${p.location}`), el('code', `[${p.id}]`), excerpt);
    if (Number.isFinite(p.rerankScore)) node.append(el('p', `模型相关性分数 ${p.rerankScore.toFixed(4)}（非置信度）`, 'muted'));
    if (p.originalUrl) node.append(originalLink(p.versionId, p.originalUrl));
    return node;
}

function addEvent(event) {
    if (event.kind === 'ANSWER_DELTA') {
        if (event.content.reset) draftAnswer = '';
        draftAnswer += event.content.delta || '';
        return;
    }
    if (event.kind === 'CONTEXT' || event.kind === 'USAGE' || event.kind === 'TOOL_CALL') return;
    const container = $('#evidence');
    if (container.querySelector('.empty-evidence')) container.replaceChildren();
    const c = event.content;
    if (event.kind === 'SQL_RESULT') {
        const evidence = sqlEvidence(c);
        evidence.dataset.evidenceCategory = 'documents';
        container.append(evidence);
        applyEvidenceFilter();
        return;
    }
    if (event.kind === 'KNOWLEDGE') {
        const rankingLabel = c.ranking?.method === 'ONNX' ? ' · 本地模型重排序' : c.ranking?.method === 'EMPTY' ? '' : ' · 排名融合';
        const retrieval = el('p', `文档检索 · ${c.mode === 'HYBRID' ? '向量 + 关键词' : '关键词'}${rankingLabel}${c.passages.length ? '' : ' · 没有检索到适用的片段。'}`, 'retrieval-label');
        retrieval.dataset.evidenceCategory = 'documents';
        container.append(retrieval);
        c.passages.forEach(p => container.append(citation(p)));
        applyEvidenceFilter();
        return;
    }
    const detail = el('details', undefined, 'event');
    detail.dataset.evidenceCategory = c.tool === 'get_effective_config' || c.tool === 'get_app_info' ? 'config' : c.tool === 'get_recent_logs' ? 'logs' : 'other';
    const summary = el('summary');
    summary.append(el('strong', toolNames[c.tool] || c.tool || event.kind), el('span', ` · ${c.state === 'success' ? '返回结果' : c.state || ''}`));
    let text = c.text || JSON.stringify(c, null, 2);
    try {
        text = JSON.stringify(JSON.parse(text), null, 2);
    } catch {
    }
    detail.append(el('div', new Date(event.createdAt).toLocaleTimeString('zh-CN'), 'event-time'), summary, el('pre', text));
    container.append(detail);
    applyEvidenceFilter();
}

let evidenceFilter = 'all';

/** 仅筛选已加载证据，不重新请求或丢弃原始事件。流式新增内容继承当前选择。 */
function applyEvidenceFilter() {
    let visible = 0;
    const entries = document.querySelectorAll('#evidence > [data-evidence-category]');
    entries.forEach(entry => {
        entry.hidden = evidenceFilter !== 'all' && entry.dataset.evidenceCategory !== evidenceFilter;
        if (!entry.hidden) visible++;
    });
    $('#evidence-filter-empty').hidden = evidenceFilter === 'all' || visible > 0;
}

/** 切换会话恢复全部分类，防止旧筛选使新会话看似丢失证据。 */
function resetEvidenceFilter() {
    evidenceFilter = 'all';
    document.querySelectorAll('[data-evidence-filter]').forEach(button => button.setAttribute('aria-pressed', String(button.dataset.evidenceFilter === 'all')));
    $('#evidence-filter-empty').hidden = true;
    applyEvidenceFilter();
}

document.querySelectorAll('[data-evidence-filter]').forEach(button => button.addEventListener('click', () => {
    evidenceFilter = button.dataset.evidenceFilter;
    document.querySelectorAll('[data-evidence-filter]').forEach(item => item.setAttribute('aria-pressed', String(item === button)));
    applyEvidenceFilter();
}));

function metricsText(run) {
    const elapsed = ['QUEUED', 'RUNNING'].includes(run.status) ? Math.max(0, Date.now() - Date.parse(run.createdAt)) : run.elapsedMs;
    const usage = currentEvents.some(e => e.kind === 'USAGE') ? `输入 ${run.inputTokens} / 输出 ${run.outputTokens} Token` : 'Token 用量尚未报告';
    return `${(elapsed / 1000).toFixed(1)} 秒 · ${usage}`;
}

function schedulePoll(id, epoch, delay = 900) {
    clearTimeout(pollTimer);
    pollTimer = setTimeout(() => loadRun(id, epoch).catch(e => pollError(id, epoch, e)), delay);
}

function pollError(id, epoch, error) {
    if (selected !== id || epoch !== pollEpoch) return;
    if (++pollFailures <= 3) {
        notify(`诊断记录暂时读取失败，正在重新连接（${pollFailures}/3）。`);
        schedulePoll(id, epoch, 1200 * pollFailures);
    } else {
        notify(`${error.message} 点击“刷新诊断”重新读取该任务；不会重新执行诊断。`);
        $('#refresh-run').hidden = false;
    }
}

async function loadRun(id, epoch = pollEpoch) {
    // Fetch terminal status before its events: a terminal snapshot must include all prior writes.
    const run = await api(`/runs/${id}`);
    if (selected !== id || epoch !== pollEpoch) return;
    const events = await api(`/runs/${id}/events?after=${currentEvents.at(-1)?.id || 0}`);
    if (selected !== id || epoch !== pollEpoch) return;
    if (pollFailures) clearNotice();
    pollFailures = 0;
    $('#refresh-run').hidden = true;
    const changed = !currentRun || run.status !== currentRun.status || run.answer !== currentRun.answer || events.some(e => e.kind === 'ANSWER_DELTA');
    currentRun = run;
    sessionId = run.sessionId;
    currentEvents.push(...events);
    events.forEach(addEvent);
    if (changed) renderRun(); else $('#run-metrics').textContent = metricsText(run);
    if (['QUEUED', 'RUNNING'].includes(run.status)) {
        if (typeof EventSource === 'undefined') schedulePoll(id, epoch);
        else connectStream(id, epoch);
    } else {
        closeStream();
        clearTimeout(pollTimer);
        await history();
    }
}

function closeStream() {
    runStream?.close();
    runStream = null;
}

function connectStream(id, epoch) {
    closeStream();
    const connection = new EventSource(`/api/runs/${id}/stream?after=${currentEvents.at(-1)?.id || 0}`);
    runStream = connection;
    connection.addEventListener('snapshot', event => {
        if (selected !== id || epoch !== pollEpoch || runStream !== connection) return;
        try {
            const snapshot = JSON.parse(event.data);
            const after = currentEvents.at(-1)?.id || 0;
            const events = snapshot.events.filter(e => e.id > after);
            const changed = !currentRun || currentRun.status !== snapshot.run.status || currentRun.answer !== snapshot.run.answer || events.some(e => e.kind === 'ANSWER_DELTA');
            currentRun = snapshot.run;
            currentEvents.push(...events);
            events.forEach(addEvent);
            if (changed) scheduleRunRender(); else $('#run-metrics').textContent = metricsText(currentRun);
            if (pollFailures) clearNotice();
            pollFailures = 0;
            if (!['QUEUED', 'RUNNING'].includes(currentRun.status)) {
                closeStream();
                history().catch(e => notify(e.message));
            }
        } catch (error) {
            closeStream();
            pollError(id, epoch, error);
        }
    });
    connection.onerror = () => {
        if (selected !== id || epoch !== pollEpoch || runStream !== connection) return;
        closeStream();
        pollError(id, epoch, new Error('流式连接中断。'));
    };
}

async function openRun(id) {
    resetEvidenceFilter();
    conversationView = null;
    closeStream();
    draftAnswer = '';
    clearTimeout(pollTimer);
    const epoch = ++pollEpoch;
    pollFailures = 0;
    selected = id;
    currentRun = null;
    currentEvents = [];
    $('#evidence').replaceChildren(el('div', '正在读取执行记录…', 'empty-evidence'));
    page('diagnosis');
    await loadRun(id, epoch).catch(e => pollError(id, epoch, e));
    await history();
}

$('#chat-form').addEventListener('submit', async event => {
    event.preventDefault();
    await action($('#start-run'), async () => {
        const run = await api('/runs', {
            method: 'POST',
            body: {question: $('#question').value, sessionId, lexicalOnly: $('#lexical-only').checked}
        });
        $('#question').value = '';
        await openRun(run.id);
    });
});
$('#cancel-run').addEventListener('click', () => action($('#cancel-run'), async () => {
    const id = selected;
    await api(`/runs/${id}/cancel`, {method: 'POST'});
    if (selected !== id) return;
    closeStream();
    clearTimeout(pollTimer);
    const epoch = ++pollEpoch;
    await loadRun(id, epoch).catch(e => pollError(id, epoch, e));
}));
$('#refresh-run').addEventListener('click', () => action($('#refresh-run'), async () => {
    closeStream();
    clearTimeout(pollTimer);
    const epoch = ++pollEpoch;
    pollFailures = 0;
    await loadRun(selected, epoch).catch(e => pollError(selected, epoch, e));
}));
function useExampleQuestion() {
    $('#question').value = '升级到 2.0 后订单同步持续失败，请查询当前环境，核查适用文档，给出有证据的原因分析和人工处理建议。';
    $('#question').focus();
}
$('#use-example').addEventListener('click', useExampleQuestion);

/** 新会话恢复完整引导空态，初次进入与清空会话采用一致布局。 */
function renderEmptyConversation() {
    const empty = el('div', undefined, 'empty-diagnosis');
    const symbol = el('span', undefined, 'empty-symbol ui-icon icon-chat');
    symbol.setAttribute('aria-hidden', 'true');
    const example = el('button', '使用示例：升级后订单同步失败', 'example-prompt');
    example.id = 'use-example';
    example.addEventListener('click', useExampleQuestion);
    empty.append(symbol, el('h2', '从一个现场问题开始'), el('p', '描述异常现象、发生时间与已经尝试的操作。诊断助手会查询当前环境，核查适用资料，整理建议与证据缺口。'), example,
        el('p', isAdmin ? '首次体验可先导入示例文档，再配置对话模型。' : '资料与对话模型由管理员维护，尚未就绪时请联系管理员。', 'subtle'));
    $('#conversation').replaceChildren(empty);
}
$('#new-session').addEventListener('click', () => {
    conversationView = null;
    closeStream();
    draftAnswer = '';
    clearTimeout(pollTimer);
    ++pollEpoch;
    pollFailures = 0;
    selected = null;
    currentRun = null;
    currentEvents = [];
    sessionId = crypto.randomUUID();
    page('diagnosis');
    renderEmptyConversation();
    resetEvidenceFilter();
    $('#evidence').replaceChildren(el('div', '等待新的诊断证据。', 'empty-evidence'));
    $('#run-state').textContent = '待开始';
    $('#diagnosis-title').textContent = '诊断工作台';
    $('#run-metrics').hidden = true;
    $('#refresh-run').hidden = true;
    $('#question').value = '';
    updateButtons();
    history().catch(e => notify(e.message));
});
$('#export-run').addEventListener('click', () => {
    if (!currentRun) return;
    const url = URL.createObjectURL(new Blob([JSON.stringify({
        run: currentRun,
        events: currentEvents
    }, null, 2)], {type: 'application/json'}));
    const link = el('a');
    link.href = url;
    link.download = `supportops-${currentRun.id}.json`;
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
});

$('#search-form').addEventListener('submit', async e => {
    e.preventDefault();
    await action(e.submitter, async () => {
        const p = new URLSearchParams({query: $('#search-query').value, version: $('#search-version').value});
        const data = await api(`/documents/search?${p}`);
        const out = $('#search-results');
        out.replaceChildren(el('p', `${data.mode === 'HYBRID' ? '向量 + 关键词' : '关键词'} · ${data.passages.length} 个片段`, 'muted'));
        data.passages.forEach(p => out.append(citation(p)));
        if (!data.passages.length) out.append(el('p', '没有匹配当前版本的资料，请调整查询内容或补充文档。'));
    });
});

async function memoryList() {
    const values = await api('/memories');
    const list = $('#memories');
    list.replaceChildren();
    if (!values.length) list.append(el('p', '暂无已确认的项目约束。', 'muted'));
    for (const item of values) {
        const row = el('div', undefined, 'data-row');
        row.append(el('div', item.content), el('p', `确认于 ${new Date(item.createdAt).toLocaleString('zh-CN')}`));
        const remove = el('button', '删除', 'text-button');
        remove.addEventListener('click', () => action(remove, async () => {
            await api(`/memories/${item.id}/delete`, {method: 'POST'});
            await memoryList();
        }));
        row.append(remove);
        list.append(row);
    }
}

$('#memory-form').addEventListener('submit', async e => {
    e.preventDefault();
    await action(e.submitter, async () => {
        await api('/memories', {
            method: 'POST',
            body: {content: $('#memory-content').value, confirmed: $('#memory-confirmed').checked}
        });
        $('#memory-content').value = '';
        $('#memory-confirmed').checked = false;
        await memoryList();
        notify('约束已保存，新诊断任务会读取。', true);
    });
});

async function demo() {
    const value = await api('/demo');
    $('#scenario').value = value.scenario;
    $('#demo-state').textContent = JSON.stringify(value, null, 2);
}

$('#set-scenario').addEventListener('click', () => action($('#set-scenario'), async () => {
    await api('/demo/scenario', {method: 'POST', body: {scenario: $('#scenario').value}});
    await demo();
    notify('已切换示例环境并产生一条新的同步记录。', true);
}));
$('#repair-demo').addEventListener('click', () => action($('#repair-demo'), async () => {
    const result = await api('/demo/repair-config', {method: 'POST'});
    await demo();
    notify(result.success ? '配置已应用，本次业务请求返回 200。' : '配置已应用，本次请求仍未成功，请检查其他原因。', result.success);
}));
$('#sync-demo').addEventListener('click', () => action($('#sync-demo'), async () => {
    await api('/demo/sync', {method: 'POST'});
    await demo();
}));
let modelSettings = null, settingsBusy = false;

function setSettingsBusy(busy) {
    settingsBusy = busy;
    document.querySelectorAll('#model-settings button, #reload-model-settings').forEach(button => button.disabled = busy);
}

const providerNames = {
    bailian: '阿里云百炼',
    deepseek: 'DeepSeek',
    openai: 'OpenAI',
    custom: '自定义兼容接口',
    disabled: '关闭向量服务'
};
Object.assign(errors, {
    MODEL_NOT_CONFIGURED: '对话模型尚未配置，请在“模型配置”中选择服务商并填写密钥。',
    MODEL_SETTINGS_CHANGED: '配置已被其他页面修改。请先记录当前输入，再点击“重新读取配置”后操作。',
    MODEL_SETTINGS_SAVE_FAILED: '配置未能写入本机，原配置仍然有效。请检查 .local 目录的写入权限后重试。',
    MODEL_SETTINGS_INVALID_INPUT: '请检查输入长度，密钥中不能包含空格或换行。',
    MODEL_SETTINGS_INVALID_PROVIDER: '服务商不可用，请重新读取配置后选择。',
    MODEL_INVALID_BASE_URL: '接口地址应为 HTTPS 基础地址（本机可用 HTTP），不含密钥、查询参数或 /chat/completions。',
    EMBEDDING_INVALID_BASE_URL: '接口地址应为 HTTPS 基础地址（本机可用 HTTP），不含密钥、查询参数或 /embeddings。',
    MODEL_NAME_REQUIRED: '请填写对话模型名称。', EMBEDDING_NAME_REQUIRED: '请填写向量模型名称。',
    MODEL_INVALID_OPTIONS: '生成参数无效，请检查文件中的输出上限、温度与推理选项。',
    EMBEDDING_UNSUPPORTED_PROVIDER: '该服务商不支持向量接口，请选择其他向量服务。'
});

async function refreshStatus() {
    status = await api('/status');
    $('#model-notice').hidden = status.modelConfigured;
    if (!isAdmin) $('#model-notice p').textContent = '对话服务尚未配置，请联系管理员完成设置。';
    $('#model-label').textContent = status.modelConfigured ? `对话模型：${status.modelRoute?.provider ?? ''} / ${status.model}` : `对话服务 ${status.modelRoute?.provider ?? ''} 尚未配置`;
    $('#embedding-status').textContent = status.embeddingConfigured ? `已配置向量服务：${status.embeddingRoute?.provider ?? ''} / ${status.embeddingModel}。上传后可手动建立索引。` : '向量服务未启用或尚未配置，当前使用关键词检索基线。';
    updateButtons();
}

async function loadModelSettings() {
    if (settingsBusy) return;
    setSettingsBusy(true);
    const container = $('#model-settings');
    container.setAttribute('aria-busy', 'true');
    try {
        modelSettings = await api('/model-settings');
        container.replaceChildren();
        for (const role of ['model', 'embedding']) container.append(modelForm(role));
        await refreshStatus();
    } finally {
        container.setAttribute('aria-busy', 'false');
        setSettingsBusy(false);
    }
}

function modelForm(role) {
    const current = modelSettings[role], vector = role === 'embedding';
    const form = el('form', undefined, 'form-panel model-form');
    form.id = `settings-${role}`;
    const heading = el('div', undefined, 'settings-heading');
    const badge = el('span', current.provider === 'disabled' ? '已关闭' : current.configured ? '已配置' : '待配置', 'badge' + (current.configured ? '' : ' pending'));
    heading.append(el('h2', vector ? '向量服务' : '对话模型'), badge);
    form.append(heading, el('p', vector ? '用于文档索引和语义检索，不影响基础诊断。' : '用于理解问题、调用排障工具并生成诊断建议。', 'muted'));

    function field(parent, name, title, tag = 'input', type = 'text') {
        const id = `${role}-${name}`, label = el('label', title), control = el(tag);
        label.htmlFor = id;
        control.id = id;
        control.name = name;
        if (tag === 'input') control.type = type;
        parent.append(label, control);
        return control;
    }

    const provider = field(form, 'provider', '服务商', 'select');
    for (const [id, preset] of Object.entries(modelSettings.providers)) {
        if (vector && !preset.embeddings) continue;
        const option = el('option', providerNames[id] || id);
        option.value = id;
        provider.append(option);
    }
    if (vector) {
        const option = el('option', providerNames.disabled);
        option.value = 'disabled';
        provider.append(option);
    }
    if (!Array.from(provider.options).some(o => o.value === current.provider)) {
        const option = el('option', `${current.provider}（当前不可用）`);
        option.value = current.provider;
        provider.append(option);
    }
    provider.value = current.provider;
    const fields = el('fieldset', undefined, 'model-fields');
    fields.append(el('legend', vector ? '向量连接参数' : '对话连接参数', 'visually-hidden'));
    form.append(fields);
    const model = field(fields, 'name', '模型 ID');
    model.value = current.model || '';
    model.maxLength = 200;
    model.required = true;
    model.placeholder = '填写接口模型 ID，而非显示名称';
    fields.append(el('p', '可从服务商目录选择，也可手动填写模型 ID。', 'field-help'));
    const discover = el('button', '获取可用模型', 'secondary');
    discover.type = 'button';
    const choices = field(fields, 'available-models', '可用模型', 'select');
    const choicesLabel = fields.querySelector('label[for="' + choices.id + '"]');
    choices.hidden = choicesLabel.hidden = true;
    fields.insertBefore(discover, choicesLabel);
    const search = field(fields, 'model-search', '搜索可用模型', 'input', 'search');
    const searchLabel = fields.querySelector('label[for="' + search.id + '"]');
    search.placeholder = '输入模型 ID 关键词，例如 qwen 或 embedding';
    search.autocomplete = 'off';
    search.setAttribute('aria-controls', choices.id);
    search.hidden = searchLabel.hidden = true;
    fields.insertBefore(searchLabel, choicesLabel);
    fields.insertBefore(search, choicesLabel);
    let availableModels = [];
    const catalogFeedback = el('p', '', 'field-help');
    catalogFeedback.setAttribute('role', 'status');
    fields.append(catalogFeedback);
    catalogFeedback.id = `${role}-catalog-feedback`;
    search.setAttribute('aria-describedby', catalogFeedback.id);
    let effort = null;
    if (!vector) {
        effort = field(fields, 'reasoning-effort', '推理等级', 'select');
        for (const [value, title] of [['', '默认（不发送）'], ['none', '无 · none'], ['minimal', '最低 · minimal'], ['low', '低 · low'], ['medium', '中 · medium'], ['high', '高 · high'], ['xhigh', '更高 · xhigh'], ['max', '最高 · max'], ['ultra', '超高 · ultra']]) {
            const option = el('option', title);
            option.value = value;
            effort.append(option);
        }
        if (current.reasoningEffort && !Array.from(effort.options).some(o => o.value === current.reasoningEffort)) {
            const option = el('option', current.reasoningEffort);
            option.value = current.reasoningEffort;
            effort.append(option);
        }
        effort.value = current.reasoningEffort || '';
        fields.append(el('p', '可用等级由模型与服务商决定；不确定时选择默认。获取模型目录不会验证推理等级。', 'field-help'));
    }
    const base = field(fields, 'base', '接口地址');
    base.value = current.baseUrl || '';
    base.maxLength = 1000;
    base.required = true;
    base.placeholder = 'https://your-gateway.example/v1';
    const baseHelp = el('p', '填写基础地址；兼容网关可在此修改。', 'field-help');
    baseHelp.id = `${role}-base-help`;
    base.setAttribute('aria-describedby', baseHelp.id);
    fields.append(baseHelp);
    const key = field(fields, 'key', 'API 密钥', 'input', 'password');
    key.maxLength = 4096;
    key.autocomplete = 'new-password';
    key.spellcheck = false;
    const keyHelp = el('p', undefined, 'field-help');
    keyHelp.id = `${role}-key-help`;
    key.setAttribute('aria-describedby', keyHelp.id);
    fields.append(keyHelp);
    const clearLabel = el('label', undefined, 'checkbox-label');
    const clear = el('input');
    clear.type = 'checkbox';
    clear.id = `${role}-clear-key`;
    clearLabel.append(clear, el('span', '清除密钥（保存后生效）'));
    clearLabel.hidden = !current.hasKey;
    fields.append(clearLabel);
    const disabledHelp = el('p', '已选择关闭。保存后使用关键词检索，已有文档与索引会保留。', 'settings-disabled');
    form.append(disabledHelp);
    const origin = el('p', current.saved ? '当前使用界面保存的配置。' : '当前使用文件与环境变量配置。', 'field-help settings-origin');
    form.append(origin);
    const feedback = el('p', '', 'settings-feedback');
    feedback.setAttribute('role', 'status');
    feedback.id = `${role}-feedback`;
    form.append(feedback);
    if (current.error && !current.error.endsWith('NOT_CONFIGURED') && current.error !== 'EMBEDDING_DISABLED') {
        feedback.textContent = errors[current.error] || '当前配置不可用，请检查服务商、模型与接口地址。';
        feedback.classList.add('error');
    }
    const actions = el('div', undefined, 'settings-actions');
    const save = el('button', vector ? '保存向量服务' : '保存对话模型', 'primary');
    save.type = 'submit';
    const reset = el('button', '恢复文件配置', 'text-button');
    reset.type = 'button';
    reset.hidden = !current.saved;
    actions.append(save, reset);
    form.append(actions);
    save.disabled = settingsBusy;
    reset.disabled = settingsBusy;
    if (vector) form.append(el('p', '更换接口或模型后，请到“知识文档”重建向量索引。', 'field-help settings-afterword'));

    function hints() {
        const changed = provider.value !== current.provider || base.value.trim().replace(/\/+$/, '') !== (current.baseUrl || '');
        key.placeholder = current.hasKey && !changed ? '已配置，留空保留原密钥' : '填写该服务商的 API 密钥';
        keyHelp.textContent = changed && current.hasKey ? '服务商或地址已变更，请填写对应密钥；旧密钥不会沿用。' : current.hasKey ? '原密钥不会回显；需要更换时输入新密钥。' : '密钥可以稍后填写，未配置时不会调用该服务。';
        const disabled = provider.value === 'disabled';
        fields.disabled = disabled;
        fields.hidden = disabled;
        disabledHelp.hidden = !disabled;
        disabledHelp.textContent = current.provider === 'disabled' ? '向量服务已关闭，当前使用关键词检索。已有文档与索引会保留。' : '已选择关闭。保存后使用关键词检索，已有文档与索引会保留。';
    }

    function filterCatalog() {
        const query = search.value.trim().toLowerCase();
        const matches = availableModels.filter(id => id.toLowerCase().includes(query));
        choices.replaceChildren();
        const placeholder = el('option', matches.length ? '请选择模型 ID' : '没有匹配的模型');
        placeholder.value = '';
        choices.append(placeholder);
        for (const id of matches) {
            const option = el('option', id);
            option.value = id;
            choices.append(option);
        }
        choices.value = matches.includes(model.value) ? model.value : '';
        catalogFeedback.textContent = query
            ? '匹配 ' + matches.length + ' / ' + availableModels.length + ' 个模型。' + (matches.length ? '选择后点击保存。' : '请更换关键词或清空搜索；当前模型 ID 保持不变。')
            : '已获取 ' + availableModels.length + ' 个模型。可输入关键词筛选，选择后点击保存；目录可能包含其他用途的模型。';
    }
    search.addEventListener('input', event => {
        event.stopPropagation();
        filterCatalog();
    });
    search.addEventListener('keydown', event => {
        if (event.key === 'Enter') event.preventDefault();
    });
    function clearCatalog() {
        availableModels = [];
        search.value = '';
        search.hidden = searchLabel.hidden = true;
        choices.replaceChildren();
        choices.hidden = choicesLabel.hidden = true;
        catalogFeedback.textContent = '';
    }
    function draft() {
        return {provider: provider.value, model: model.value, baseUrl: base.value,
            apiKey: key.value, keyAction: clear.checked ? 'remove' : key.value ? 'replace' : 'keep',
            revision: modelSettings.revision, reasoningEffort: effort?.value ?? ''};
    }
    choices.addEventListener('change', () => {
        if (!choices.value) return;
        model.value = choices.value;
        model.dispatchEvent(new Event('input', {bubbles: true}));
    });
    discover.addEventListener('click', async () => {
        if (settingsBusy) return;
        const body = draft();
        clearCatalog();
        setSettingsBusy(true);
        provider.disabled = fields.disabled = true;
        discover.textContent = '正在获取…';
        catalogFeedback.textContent = '正在读取服务商模型目录…';
        try {
            const result = await api('/model-settings/' + role + '/models', {method: 'POST', body});
            availableModels = result.models;
            choices.hidden = choicesLabel.hidden = search.hidden = searchLabel.hidden = availableModels.length === 0;
            if (availableModels.length) {
                filterCatalog();
            } else {
                catalogFeedback.textContent = '服务商返回空目录，可手动填写模型 ID 或稍后重试。';
            }
        } catch (error) {
            catalogFeedback.textContent = error.message;
        } finally {
            setSettingsBusy(false);
            provider.disabled = false;
            hints();
            discover.textContent = '获取可用模型';
            discover.focus();
        }
    });
    for (const control of [base, key, clear]) control.addEventListener('input', clearCatalog);
    provider.addEventListener('change', () => {
        clearCatalog();
        if (effort) effort.value = '';
        const p = modelSettings.providers[provider.value];
        model.value = (vector ? p?.embeddingModel : p?.model) || '';
        base.value = p?.baseUrl || '';
        key.value = '';
        clear.checked = false;
        hints();
        feedback.textContent = '配置尚未保存。';
    });
    base.addEventListener('input', hints);
    key.addEventListener('input', () => {
        if (key.value) clear.checked = false;
    });
    clear.addEventListener('change', () => {
        if (clear.checked) key.value = '';
    });
    form.addEventListener('input', () => {
        feedback.textContent = '配置尚未保存。';
        feedback.classList.remove('error');
        base.removeAttribute('aria-invalid');
    });

    async function persist(request, restoring = false) {
        if (settingsBusy) return;
        setSettingsBusy(true);
        provider.disabled = true;
        fields.disabled = true;
        feedback.textContent = restoring ? '正在恢复…' : '正在保存…';
        feedback.classList.remove('error');
        let replacement = null;
        try {
            modelSettings = await request();
            key.value = '';
            const next = modelForm(role);
            form.replaceWith(next);
            replacement = next;
            const saved = modelSettings[role];
            $(`#${role}-feedback`).textContent = restoring ? '已恢复文件与环境变量配置。' : saved.provider === 'disabled' ? '已保存，向量服务已关闭。' : saved.configured ? '配置已保存。服务连通性将在实际调用时验证。' : '配置已保存，填写密钥后即可使用。';
            try {
                await refreshStatus();
            } catch {
                $(`#${role}-feedback`).textContent += ' 服务状态暂未刷新，请刷新页面。';
            }
        } catch (e) {
            feedback.textContent = e.message;
            feedback.classList.add('error');
            if (e.code?.endsWith('INVALID_BASE_URL')) {
                base.setAttribute('aria-invalid', 'true');
                base.setAttribute('aria-describedby', `${baseHelp.id} ${feedback.id}`);
            }
        } finally {
            setSettingsBusy(false);
            provider.disabled = false;
            hints();
            if (replacement) replacement.querySelector('button[type="submit"]').focus();
        }
    }

    form.addEventListener('submit', e => {
        e.preventDefault();
        const body = draft();
        persist(() => api(`/model-settings/${role}`, {method: 'POST', body}));
    });
    reset.addEventListener('click', () => persist(() => api(`/model-settings/${role}/reset?revision=${encodeURIComponent(modelSettings.revision)}`, {method: 'POST'}), true));
    hints();
    return form;
}

$('#reload-model-settings').addEventListener('click', () => {
    if (!settingsBusy) action($('#reload-model-settings'), loadModelSettings);
});


// 用量分析只读取持久化数据；每次查询标记版本，防止迟到结果覆盖新筛选。
async function loadUsage() {
    if (!$('#usage-from').value) {
        const now = new Date(), start = new Date(now);
        start.setUTCDate(start.getUTCDate() - 6);
        $('#usage-from').value = start.toISOString().slice(0, 10);
        $('#usage-to').value = now.toISOString().slice(0, 10);
    }
    destroyUsageCharts();
    destroyUsageCharts = () => {};
    const epoch = ++usageEpoch;
    ++usageDetailEpoch;
    $('#usage-detail').hidden = true;
    const results = $('#usage-results');
    results.replaceChildren(); results.setAttribute('aria-busy', 'true');
    $('#usage-feedback').dataset.state = 'loading';
    $('#usage-feedback').textContent = '正在读取用量并更新图表…';
    $('#usage-refresh').disabled = true;
    try {
        const query = new URLSearchParams({from: $('#usage-from').value, to: $('#usage-to').value, granularity: $('#usage-granularity').value});
        const data = await api('/usage?' + query);
        if (epoch !== usageEpoch) return;
        renderUsage(data);
        $('#usage-feedback').dataset.state = 'ready';
        $('#usage-feedback').textContent = data.from + ' 至 ' + data.to + ' · UTC · 已读取最新记录';
    } catch (error) {
        if (epoch === usageEpoch) {
            $('#usage-feedback').dataset.state = 'error';
            $('#usage-feedback').textContent = '查询失败，请检查日期范围（最多 90 天）后重试。' + error.message;
        }
    } finally {
        if (epoch === usageEpoch) { results.setAttribute('aria-busy', 'false'); $('#usage-refresh').disabled = false; }
    }
}
function renderUsage(data) {
    destroyUsageCharts = renderUsageDashboard($('#usage-results'), data, {el, onSession: loadUsageSession, Chart: globalThis.Chart});
}
async function loadUsageSession(sessionId, offset) {
    const epoch = ++usageDetailEpoch, root = $('#usage-detail');
    const active = document.activeElement;
    if (!active || !root.contains(active)) usageReturnFocus = active && active !== document.body ? active : $('#usage-refresh');
    root.hidden = false; root.replaceChildren(el('h2', '会话回看'), el('p', '正在读取历史问答…'));
    root.tabIndex = -1;
    root.focus({preventScroll: true});
    root.scrollIntoView({block: 'start'});
    try {
        const items = await api('/usage/sessions/' + encodeURIComponent(sessionId) + '?offset=' + offset);
        if (epoch !== usageDetailEpoch) return;
        const detailHeading = el('div', undefined, 'usage-detail-top');
        const close = el('button', '收起回看', 'secondary');
        close.addEventListener('click', () => {
            ++usageDetailEpoch; root.hidden = true;
            const target = usageReturnFocus?.isConnected ? usageReturnFocus : $('#usage-refresh');
            target.focus();
        });
        detailHeading.append(el('h2', '会话回看'), close);
        root.replaceChildren(detailHeading, el('p', '会话 ' + sessionId + ' · 全部历史 · 第 ' + (offset / 50 + 1) + ' 页', 'muted'));
        if (!items.length) root.append(el('p', '没有更多问答。'));
        items.forEach((item, index) => {
            const article = el('article', undefined, 'usage-turn');
            article.append(el('h3', '第 ' + (offset + index + 1) + ' 轮 · ' + (statuses[item.status] || item.status)), el('p', item.createdAt + ' · 输入 ' + tokenNumber(item.inputTokens) + ' / 输出 ' + tokenNumber(item.outputTokens) + ' Token · ' + (item.elapsedMs / 1000).toFixed(1) + ' 秒', 'muted'));
            article.append(el('h4', '用户提问'), el('p', item.question, 'usage-content usage-user-message'), el('h4', '助手回答'), el('p', item.answer || '暂无最终回答；可打开执行记录查看已保存的片段。', 'usage-content'));
            if (item.errorCode) article.append(el('p', '结束原因：' + item.errorCode));
            const button = el('button', '查看执行证据', 'secondary');
            button.addEventListener('click', () => openRun(item.id).catch(error => notify(error.message)));
            article.append(button); root.append(article);
        });
        const actions = el('div', undefined, 'usage-pagination');
        if (offset > 0) { const previous = el('button', '上一页', 'secondary'); previous.addEventListener('click', () => loadUsageSession(sessionId, offset - 50)); actions.append(previous); }
        if (items.length === 50) { const next = el('button', '下一页', 'secondary'); next.addEventListener('click', () => loadUsageSession(sessionId, offset + 50)); actions.append(next); }
        root.append(actions);
    } catch (error) {
        if (epoch === usageDetailEpoch) {
            root.replaceChildren(el('h2', '会话读取失败'), el('p', error.message));
            const retry = el('button', '重试', 'secondary'); retry.addEventListener('click', () => loadUsageSession(sessionId, offset)); root.append(retry);
        }
    }
}
$('#usage-filter').addEventListener('submit', event => { event.preventDefault(); loadUsage(); });

document.querySelectorAll('[data-usage-days]').forEach(button => button.addEventListener('click', () => {
    const days = Number(button.dataset.usageDays);
    const end = new Date(), start = new Date(end);
    start.setUTCDate(start.getUTCDate() - days + 1);
    $('#usage-from').value = start.toISOString().slice(0, 10);
    $('#usage-to').value = end.toISOString().slice(0, 10);
    if (days === 1) $('#usage-granularity').value = 'hour';
    document.querySelectorAll('[data-usage-days]').forEach(item => item.setAttribute('aria-pressed', String(item === button)));
    loadUsage();
}));
['#usage-from', '#usage-to'].forEach(selector => $(selector).addEventListener('input', () => {
    document.querySelectorAll('[data-usage-days]').forEach(item => item.setAttribute('aria-pressed', 'false'));
}));
try {
    if (isAdmin) await loadUsers();
    await refreshStatus();
    await history();
} catch (e) {
    notify('无法连接本地服务，请检查应用是否启动。');
}

async function loadUsers() {
    const users = await api('/users');
    const target = $('#user-list'); target.replaceChildren();
    for (const user of users) {
        userNames.set(user.id, user.username);
        const row = el('div', undefined, 'user-row');
        const identity = el('div');
        identity.append(el('strong', user.username), el('p', '创建于 ' + new Date(user.createdAt).toLocaleString('zh-CN')));
        row.append(identity, el('span', user.role === 'ADMIN' ? '管理员' : '普通用户', 'badge'));
        target.append(row);
    }
}
$('#logout').addEventListener('click', () => action($('#logout'), async () => {
    await api('/auth/logout', {method: 'POST'});
    location.replace('/login.html');
}));
$('#create-user-form').addEventListener('submit', event => {
    event.preventDefault();
    action($('#create-user-submit'), async () => {
        $('#user-feedback').textContent = '';
        const result = await api('/users', {method: 'POST', body: {
            username: $('#new-username').value, password: $('#new-password').value, role: $('#new-role').value
        }});
        $('#create-user-form').reset();
        $('#user-feedback').textContent = '已创建账号：' + result.username;
        await loadUsers();
    });
});
