import {validChunkOptions, batchChunkOptions, batchChunkLabel} from './chunk-settings.mjs';

export function initializeKnowledge({api, el, ownerLabel, canManage, errors, notify, action, $, getStatus}) {
/* 知识文档扩展沿用现有浅色工作区：上传在左、状态列表在右，修订详情在下方就地展开。
   上传成功只表示已受理；异步状态从服务端读取，编辑中的导入参数不会被轮询覆盖。 */
const knowledgeStates = {
    PENDING: '等待处理', RUNNING: '处理中', READY: '解析完成', PREVIEW: '待确认预览',
    SUCCEEDED: '已完成', FAILED: '失败', BLOCKED: '等待处理条件', RETRY_WAIT: '等待自动重试',
    CANCELLED: '已取消', SUPERSEDED: '已被新任务替代', NOT_APPLICABLE: '不适用',
    RECEIVING: '正在保存原件', ACCEPTED: '已受理', DUPLICATE: '重复上传，已复用',
    STAGING: '正在入表', REPLACED: '历史暂存数据', DELETED: '已删除', LEGACY: '历史数据'
};
const taskKinds = {IMPORT: '解析与入库', TEXT: '关键词索引', VECTOR: '向量索引', CLEANUP: '文件与索引清理', FILE_CLEANUP: '未关联原件清理'};
Object.assign(errors, {
    INVALID_IMPORT_OPTIONS: '分片上限须为 100 至 8000，重叠字数非负且每次至少前进 10 字。',
    FILE_TOO_LARGE: '文件超过 20 MB，请缩小后上传。',
    MINIO_NOT_CONFIGURED: '原件存储尚未配置，请设置 MinIO 连接。',
    MINIO_WRITE_FAILED: '原件保存失败，请检查 MinIO 后重试。',
    ES_UNAVAILABLE: '检索服务暂时不可用，请检查 Elasticsearch 连接。',
    SQL_POLICY_REJECTED: '查询超出支持范围，请使用 data 表和已列出的列名编写单表 SELECT。',
    SQL_QUERY_FAILED: '查询未完成，请检查汇总字段、列类型或只读数据库连接。',
    DATASET_NOT_APPLICABLE: '该数据集尚未发布、已被替换或不适用当前产品版本。',
    ORIGINAL_FILE_MISSING: '这份历史修订没有保存原件，仍可核对已留存的分片。',
    TASK_NOT_RETRYABLE: '当前任务状态不能重试，请刷新后查看。',
    DOCUMENT_NOT_SEARCHABLE: '请等待解析和关键词索引完成，再重建向量。',
    EXCEL_PARSE_FAILED: 'Excel 解析失败，请检查工作簿格式、表头及公式缓存结果。'
});
let knowledgeItems = [], knowledgeListSignature = '', knowledgeDocumentId = null;
let knowledgeRevisionId = null, knowledgeDetailSignature = '', knowledgeRefreshBusy = false;
let uploadRequest = null;
const queryDrafts = new Map();

function knowledgeState(value) { return knowledgeStates[value] || value || '尚未开始'; }
function knowledgeError(value) { return errors[value] || value || ''; }
function knowledgeBadge(value) {
    return el('span', knowledgeState(value), 'badge' + (['FAILED', 'BLOCKED'].includes(value) ? ' error' : ['PENDING', 'RUNNING', 'RETRY_WAIT', 'PREVIEW'].includes(value) ? ' pending' : ''));
}

function originalLink(versionId, providedUrl) {
    if (!canManage) return el('span', '已展示本次诊断引用片段；完整原件由管理员管理。', 'muted');
    const link = el('a', '查看该修订原件', 'original-link');
    // 只允许服务端版本接口，历史事件中的 URL 不能扩大导航范围。
    const canonical = `/api/documents/versions/${encodeURIComponent(versionId)}/original`;
    link.href = providedUrl?.startsWith(canonical + '#page=') && /^\d+$/.test(providedUrl.split('#page=')[1]) ? providedUrl : canonical;
    link.target = '_blank'; link.rel = 'noopener';
    return link;
}

async function documents() {
    const values = await api('/documents');
    knowledgeItems = values;
    const signature = JSON.stringify(values);
    if (signature === knowledgeListSignature) return;
    knowledgeListSignature = signature;
    const list = $('#documents'); list.replaceChildren();
    if (!values.length) list.append(el('p', '暂无文档。上传产品资料，或导入示例文档。', 'muted'));
    for (const item of values) {
        const row = el('article', undefined, 'data-row');
        const title = el('button', item.title, 'citation-title');
        title.addEventListener('click', () => openKnowledgeDetail(item.id).catch(error => notify(error.message)));
        row.append(title, el('p', `产品版本 ${item.version} · ${item.filename} · ${item.chunks} 个片段`));
        const states = el('div', undefined, 'knowledge-statuses');
        for (const [label, value] of [['解析', item.processingStatus], ['关键词', item.textStatus], ['向量', item.vectorStatus]]) {
            if (value !== 'NOT_APPLICABLE') { const state = el('span', label + ' '); state.append(knowledgeBadge(value)); states.append(state); }
        }
        if (/\.xlsx?$/i.test(item.filename)) states.append(el('span', 'Excel 关系数据 · 使用表格查询', 'field-help'));
        const actions = el('div', undefined, 'row-actions');
        const detail = el('button', '版本与处理记录');
        detail.addEventListener('click', () => action(detail, () => openKnowledgeDetail(item.id)));
        const revise = el('button', '上传新修订');
        revise.addEventListener('click', () => selectRevisionUpload(item));
        actions.append(detail, revise);
        if (!/\.xlsx?$/i.test(item.filename)) {
            const index = el('button', '重建向量');
            index.disabled = !getStatus()?.embeddingConfigured || item.textStatus !== 'SUCCEEDED';
            index.addEventListener('click', () => action(index, async () => {
                await api(`/documents/${item.id}/index`, {method: 'POST'});
                await documents(); notify('已受理向量重建，可在处理记录中查看进度。', true);
            }));
            actions.append(index);
        }
        const remove = el('button', '删除', 'delete');
        remove.addEventListener('click', () => action(remove, async () => {
            await api(`/documents/${item.id}/delete`, {method: 'POST'});
            if (knowledgeDocumentId === item.id) { knowledgeDocumentId = null; $('#knowledge-detail').hidden = true; }
            await documents(); notify('文档已停止检索，原件和索引清理由后台继续完成。', true);
        }));
        actions.append(remove); row.append(states, actions); list.append(row);
    }
}

function selectRevisionUpload(item) {
    $('#document-owner').value = item.id; $('#document-title').value = item.title;
    $('#document-version').value = item.version;
    $('#revision-target').textContent = `将为“${item.title}”新增修订，旧修订会保留。`;
    $('#revision-target').hidden = false; $('#cancel-revision').hidden = false;
    $('#upload-form').scrollIntoView({block: 'start'}); $('#document-file').focus();
}

$('#cancel-revision').addEventListener('click', () => {
    $('#document-owner').value = ''; $('#revision-target').hidden = true; $('#cancel-revision').hidden = true;
});

function uploadOptions() {
    const sheetText = $('#excel-sheets').value.trim();
    if (sheetText && !/^\d+(\s*[,，]\s*\d+)*$/.test(sheetText)) throw new Error('Sheet 序号从 0 开始，请用逗号分隔。');
    const options = {chunkSize: Number($('#chunk-size').value), overlap: Number($('#chunk-overlap').value),
        sheets: sheetText ? sheetText.split(/[,，]/).map(Number) : [], headerRow: Number($('#excel-header').value), columnTypes: {}, preview: $('#excel-preview').checked};
    if (!validChunkOptions(options.chunkSize, options.overlap)) throw new Error(errors.INVALID_IMPORT_OPTIONS);
    return options;
}

$('#upload-form').addEventListener('submit', async event => {
    event.preventDefault();
    await action(event.submitter, async () => {
        const file = $('#document-file').files[0];
        if (!file || file.size > 20 * 1024 * 1024) throw new Error(errors.FILE_TOO_LARGE);
        const options = uploadOptions();
        const signature = JSON.stringify([file.name, file.size, file.lastModified, $('#document-title').value,
            $('#document-version').value, $('#document-owner').value, $('#document-note').value, options]);
        if (!uploadRequest || uploadRequest.signature !== signature) uploadRequest = {signature, key: crypto.randomUUID()};
        const data = new FormData();
        data.set('file', file); data.set('title', $('#document-title').value); data.set('version', $('#document-version').value);
        if ($('#document-owner').value) data.set('documentId', $('#document-owner').value);
        data.set('note', $('#document-note').value); data.set('requestKey', uploadRequest.key);
        data.set('options', new Blob([JSON.stringify(options)], {type: 'application/json'}));
        $('#upload-feedback').textContent = '正在保存原件并登记处理任务…';
        try {
            const upload = await api('/documents/uploads', {method: 'POST', body: data});
            if (upload.status === 'FAILED') {
                uploadRequest = null;
                throw new Error(`已确认该次上传失败（${knowledgeError(upload.errorCode)}），再次提交将创建新的上传记录`);
            }
            $('#upload-feedback').textContent = `${knowledgeState(upload.status)}。上传编号：${upload.id}`;
            $('#document-file').value = ''; uploadRequest = null;
            await documents(); await openKnowledgeDetail(upload.documentId, upload.versionId);
        } catch (error) {
            $('#upload-feedback').textContent = `上传未完成：${error.message}。输入已保留，可再次提交核对同一次请求。`;
            throw error;
        }
    });
});

$('#import-examples').addEventListener('click', () => action($('#import-examples'), async () => {
    await api('/documents/import-examples', {method: 'POST'});
    await documents(); notify('示例文档已受理，分片与索引进度会自动更新。', true);
}));

async function showDocument(id, batchId) {
    const data = await api(`/documents/${encodeURIComponent(id)}`);
    const chunks = batchId ? await api(`/documents/${encodeURIComponent(id)}/chunks?batchId=${encodeURIComponent(batchId)}`) : data.chunks;
    $('#dialog-title').textContent = data.document.title;
    const body = $('#dialog-content'); body.replaceChildren();
    if (!chunks.length) body.append(el('p', '此批次没有文本分片。Excel 请查看数据集；文档正在处理时请稍后刷新。', 'muted'));
    if (chunks.length) body.append(el('p', '下方显示每片实际字数；章节末片可以短于上限，重叠不跨章节或 PDF 页。', 'field-help'));
    for (const part of chunks) body.append(el('h3', part.location), el('code', part.id),
        el('p', `本片 ${Array.from(part.content).length} 字`, 'field-help'), el('pre', part.content, 'passage-content'));
    if (!$('#document-dialog').open) $('#document-dialog').showModal();
}
$('#close-dialog').addEventListener('click', () => $('#document-dialog').close());

async function openKnowledgeDetail(id, revisionId = null, quiet = false) {
    const changedDocument = knowledgeDocumentId !== id;
    if (changedDocument) knowledgeRevisionId = revisionId;
    if (revisionId) knowledgeRevisionId = revisionId;
    knowledgeDocumentId = id;
    const [versions, batches, tasks, uploads] = await Promise.all([
        api(`/documents/${id}/versions`), api(`/documents/${id}/batches`), api(`/documents/${id}/tasks`), api(`/documents/uploads?documentId=${id}`)
    ]);
    if (knowledgeDocumentId !== id) return;
    knowledgeRevisionId = versions.some(version => version.id === knowledgeRevisionId) ? knowledgeRevisionId : versions[0]?.id;
    const signature = JSON.stringify([id, knowledgeRevisionId, versions, batches, tasks, uploads]);
    if (quiet && (signature === knowledgeDetailSignature || $('#knowledge-detail').contains(document.activeElement) && document.activeElement.matches('input,textarea,select'))) return;
    knowledgeDetailSignature = signature;
    const box = $('#knowledge-detail'); box.hidden = false; box.replaceChildren();
    const heading = el('div', undefined, 'page-heading'); heading.append(el('h2', knowledgeItems.find(item => item.id === id)?.title || '文档处理记录'));
    const close = el('button', '收起', 'secondary'); close.addEventListener('click', () => { box.hidden = true; knowledgeDocumentId = null; }); heading.append(close); box.append(heading);
    const choiceLabel = el('label', '文档修订'); choiceLabel.htmlFor = 'knowledge-revision';
    const select = el('select'); select.id = 'knowledge-revision';
    versions.forEach(version => { const option = el('option', `修订 ${version.revision} · 产品版本 ${version.productVersion} · ${new Date(version.createdAt).toLocaleString('zh-CN')}`); option.value = version.id; select.append(option); });
    knowledgeRevisionId = versions.some(version => version.id === knowledgeRevisionId) ? knowledgeRevisionId : versions[0]?.id;
    if (knowledgeRevisionId) select.value = knowledgeRevisionId;
    select.addEventListener('change', () => openKnowledgeDetail(id, select.value).catch(error => notify(error.message)));
    box.append(choiceLabel, select);
    const version = versions.find(item => item.id === knowledgeRevisionId);
    if (version) {
        box.append(el('p', version.note || '未填写修订说明', 'field-help'));
        if (version.fileStatus === 'MISSING') {
            const form = el('form'); const label = el('label', '补传缺失的历史原件'); const input = el('input'); input.type = 'file'; input.required = true; label.append(input);
            const submit = el('button', '核对摘要并补传', 'secondary');
            form.append(label, el('p', '原文件内容必须与历史摘要完全一致。新内容请使用“上传新修订”。', 'field-help'), submit);
            form.addEventListener('submit', event => { event.preventDefault(); action(submit, async () => {
                const data = new FormData(); data.set('file', input.files[0]);
                await api(`/documents/versions/${version.id}/original`, {method: 'POST', body: data});
                await openKnowledgeDetail(id, version.id); notify('历史原件已恢复。', true);
            }); });
            box.append(form);
        } else box.append(originalLink(version.id));
        const selectedBatches = batches.filter(item => item.versionId === version.id);
        await renderKnowledgeBatches(box, version, selectedBatches, tasks);
    }
    const history = el('details', undefined, 'knowledge-history'); history.append(el('summary', `上传记录（${uploads.length}）`), uploadTable(uploads)); box.append(history);
    if (!quiet) box.scrollIntoView({block: 'start'});
}

async function renderKnowledgeBatches(box, version, batches, tasks) {
    const latest = batches[0];
    if (!latest) { box.append(el('p', '尚未创建处理批次。', 'muted')); return; }
    const tabular = ['XLS', 'XLSX'].includes(version.fileType);
    let datasets = [];
    if (tabular) datasets = await api(`/documents/batches/${latest.id}/datasets`);
    if (knowledgeRevisionId !== version.id) return;
    const title = el('h3', '处理批次'); box.append(title);
    for (const batch of batches) {
        const row = el('div', undefined, 'knowledge-batch');
        row.append(el('p', `${new Date(batch.createdAt).toLocaleString('zh-CN')} · ${tabular ? batch.rowCount + ' 行数据' : batch.chunkCount + ' 个片段'}`), knowledgeBadge(batch.status));
        row.append(el('p', `批次 ${batch.id}`, 'field-help'));
        if (batch.errorCode) row.append(el('p', knowledgeError(batch.errorCode), 'knowledge-error'));
        if (!tabular) {
            const view = el('button', batchChunkLabel(batch), 'text-button');
            view.addEventListener('click', () => action(view, () => showDocument(version.documentId, batch.id))); row.append(view);
        }
        const batchTasks = tasks.filter(task => task.batchId === batch.id);
        batchTasks.forEach(task => row.append(taskRow(task, version.documentId)));
        box.append(row);
    }
    const settings = el('details', undefined, 'knowledge-reprocess');
    settings.open = latest.status === 'PREVIEW';
    settings.append(el('summary', latest.status === 'PREVIEW' ? '确认 Excel 列类型并导入' : '使用原件重新处理'));
    const form = el('form');
    const fieldGroup = el('div', undefined, 'knowledge-fields');
    const defaults = batchChunkOptions(latest);
    const size = numberField('每片字数上限', defaults.chunkSize, 100, 8000);
    const overlap = numberField('重叠字数', defaults.overlap, 0, 7990);
    fieldGroup.append(size.label, overlap.label); fieldGroup.hidden = tabular; form.append(fieldGroup);
    if (tabular) {
        if (!datasets.length) form.append(el('p', '数据结构尚未生成。处理完成后在这里查看表头、样例及列类型。', 'field-help'));
        for (const dataset of datasets.filter(item => ['PREVIEW', 'READY'].includes(item.status))) {
            const section = el('section', undefined, 'excel-dataset');
            section.append(el('h4', `${dataset.sheetName} · ${dataset.rowCount} 行`), el('p', `Sheet 序号 ${dataset.sheetIndex} · 表头第 ${dataset.headerRow} 行`, 'field-help'));
            const wrap = el('div', undefined, 'knowledge-table-wrap');
            const table = el('table', undefined, 'knowledge-table');
            table.append(tableHeader(['原始表头', '查询列名', '列类型', '样例（最多 5 个）']));
            const tbody = el('tbody');
            for (const column of dataset.columns) {
                const row = el('tr'); row.append(el('td', column.originalHeader), el('td', column.columnName));
                const cell = el('td'); const select = el('select'); select.name = `${dataset.sheetIndex}:${column.columnIndex}`;
                select.setAttribute('aria-label', `${dataset.sheetName} ${column.originalHeader} 的列类型`);
                for (const [type, label] of [['TEXT', '文本'], ['INTEGER', '整数'], ['DECIMAL', '精确小数'], ['DATE', '日期'], ['DATETIME', '日期时间'], ['BOOLEAN', '是/否']]) {
                    const option = el('option', label); option.value = type; select.append(option);
                }
                select.value = column.dataType; cell.append(select); row.append(cell, el('td', column.samples.join('；'))); tbody.append(row);
            }
            table.append(tbody); wrap.append(table); section.append(wrap); form.append(section);
            if (dataset.status === 'READY') box.append(datasetQuery(dataset, version.productVersion));
        }
    }
    const submit = el('button', tabular ? '按上述类型重新导入' : '创建新的分片批次', 'secondary'); submit.type = 'submit';
    if (latest.status === 'PREVIEW') submit.textContent = '确认并导入关系数据库';
    form.append(submit, el('p', '将保留修订和处理历史，完整处理成功后再发布新结果。', 'field-help'));
    form.addEventListener('submit', event => {
        event.preventDefault();
        action(submit, async () => {
            if (!validChunkOptions(Number(size.input.value), Number(overlap.input.value))) throw new Error(errors.INVALID_IMPORT_OPTIONS);
            const columnTypes = Object.fromEntries([...form.querySelectorAll('select[name]')].map(select => [select.name, select.value]));
            const selectedDatasets = datasets.filter(item => ['PREVIEW', 'READY'].includes(item.status));
            await api(`/documents/versions/${version.id}/reprocess`, {method: 'POST', body: {
                chunkSize: Number(size.input.value), overlap: Number(overlap.input.value),
                sheets: [...new Set(selectedDatasets.map(item => item.sheetIndex))], headerRow: selectedDatasets.length === 1 ? selectedDatasets[0].headerRow : 0,
                columnTypes, preview: false
            }});
            await openKnowledgeDetail(version.documentId, version.id); notify('新的处理批次已受理。', true);
        });
    });
    settings.append(form); box.append(settings);
}

function numberField(text, value, min, max) {
    const label = el('label', text); const input = el('input'); input.type = 'number'; input.value = value; input.min = min; input.max = max; input.required = true;
    label.append(input); return {label, input};
}

function taskRow(task, documentId) {
    const row = el('div', undefined, 'knowledge-task');
    row.append(el('strong', taskKinds[task.kind] || task.kind), knowledgeBadge(task.status));
    const progress = task.kind === 'VECTOR' || task.kind === 'TEXT' ? ` · 已确认 ${task.completedChunks} 片` : '';
    row.append(el('span', `尝试 ${task.attemptCount} 次${progress}`, 'field-help'));
    if (task.status === 'RETRY_WAIT') row.append(el('p', `下次补偿：${new Date(task.nextRetryAt).toLocaleString('zh-CN')}`, 'field-help'));
    if (task.errorCode) row.append(el('p', knowledgeError(task.errorCode), 'knowledge-error'));
    if (['FAILED', 'BLOCKED', 'RETRY_WAIT'].includes(task.status)) {
        const retry = el('button', '重试', 'text-button');
        retry.addEventListener('click', () => action(retry, async () => { await api(`/documents/tasks/${task.id}/retry`, {method: 'POST'}); await openKnowledgeDetail(documentId, knowledgeRevisionId); })); row.append(retry);
    }
    const history = el('details'); history.append(el('summary', '执行历史'));
    history.addEventListener('toggle', async () => {
        if (!history.open || history.dataset.loaded) return;
        try {
            const attempts = await api(`/documents/tasks/${task.id}/attempts`);
            history.append(el('p', attempts.length ? attempts.map(item => `第 ${item.attemptNumber} 次 · ${knowledgeState(item.status)} · ${new Date(item.startedAt).toLocaleString('zh-CN')}${item.errorCode ? ' · ' + knowledgeError(item.errorCode) : ''}`).join('\n') : '尚未开始执行。', 'task-attempts'));
            history.dataset.loaded = 'true';
        } catch (error) { notify(error.message); }
    });
    row.append(history); return row;
}

function tableHeader(labels) {
    const head = el('thead'), row = el('tr');
    labels.forEach(label => { const cell = el('th', label); cell.scope = 'col'; row.append(cell); }); head.append(row); return head;
}

function uploadTable(uploads) {
    const wrap = el('div', undefined, 'knowledge-table-wrap');
    if (!uploads.length) { wrap.append(el('p', '暂无上传记录。', 'muted')); return wrap; }
    const table = el('table', undefined, 'knowledge-table'); table.append(tableHeader(['文件', '上传用户', '大小', '受理时间', '结果']));
    const body = el('tbody');
    uploads.forEach(upload => {
        const row = el('tr'); row.append(el('td', upload.filename), el('td', ownerLabel(upload.userId)), el('td', `${(upload.sizeBytes / 1024).toFixed(1)} KB`), el('td', new Date(upload.createdAt).toLocaleString('zh-CN')));
        const status = el('td'); status.append(knowledgeBadge(upload.status));
        if (upload.errorCode) status.append(el('p', knowledgeError(upload.errorCode), 'knowledge-error'));
        row.append(status); body.append(row);
    }); table.append(body); wrap.append(table); return wrap;
}

$('#upload-history').addEventListener('toggle', async () => {
    if (!$('#upload-history').open) return;
    try { $('#upload-history-content').replaceChildren(uploadTable(await api('/documents/uploads'))); }
    catch (error) { $('#upload-history-content').textContent = error.message; }
});

function datasetQuery(dataset, version) {
    const section = el('details', undefined, 'excel-query'); section.append(el('summary', `查询 ${dataset.sheetName}`));
    if (!queryDrafts.has(dataset.id)) queryDrafts.set(dataset.id, {sql: 'SELECT * FROM data LIMIT 20', open: false});
    const draft = queryDrafts.get(dataset.id);
    section.open = draft.open;
    section.addEventListener('toggle', () => { draft.open = section.open; });
    section.append(el('p', dataset.columns.map(column => `${column.columnName}：${column.originalHeader}（${column.dataType}）`).join('；'), 'field-help'));
    const form = el('form'); const label = el('label', '查询语句'); const sql = el('textarea'); sql.rows = 3; sql.maxLength = 8000; sql.required = true;
    sql.value = draft.sql;
    sql.addEventListener('input', () => { draft.sql = sql.value; });
    label.append(sql); form.append(label);
    form.append(el('p', '只读查询。使用上方列名，可筛选、排序或汇总；表名固定为 data，source_row 是原始行号。', 'field-help'));
    const submit = el('button', '执行查询', 'secondary'); const output = el('div'); output.setAttribute('aria-live', 'polite'); form.append(submit);
    if (draft.result) output.append(sqlEvidence(draft.result));
    form.addEventListener('submit', event => {
        event.preventDefault(); action(submit, async () => {
            output.replaceChildren(el('p', '正在查询…', 'muted'));
            try { const result = await api(`/documents/datasets/${dataset.id}/query`, {method: 'POST', body: {version, sql: sql.value}}); draft.result = result; output.replaceChildren(sqlEvidence(result)); }
            catch (error) { output.replaceChildren(el('p', error.message, 'knowledge-error')); throw error; }
        });
    }); section.append(form, output); return section;
}

function sqlEvidence(result) {
    const box = el('div', undefined, 'sql-evidence');
    box.append(el('h4', `Excel 查询 · ${result.sheetName}`), originalLink(result.versionId), el('pre', result.sql));
    if (result.truncated) box.append(el('p', '结果已达到返回上限，以下不是全部明细。请缩小范围或分页。', 'knowledge-error'));
    const wrap = el('div', undefined, 'knowledge-table-wrap');
    if (!result.rows.length) wrap.append(el('p', '没有符合条件的数据。', 'muted'));
    else {
        const table = el('table', undefined, 'knowledge-table'); table.append(tableHeader(result.columns)); const body = el('tbody');
        result.rows.forEach(values => { const row = el('tr'); values.forEach(value => row.append(el('td', value === null ? 'NULL' : value))); body.append(row); });
        table.append(body); wrap.append(table);
    }
    box.append(wrap); return box;
}

setInterval(async () => {
    if ($('#page-knowledge').hidden || document.hidden || knowledgeRefreshBusy) return;
    knowledgeRefreshBusy = true;
    try {
        await documents();
        if (knowledgeDocumentId) await openKnowledgeDetail(knowledgeDocumentId, knowledgeRevisionId, true);
    } catch (error) {
        $('#upload-feedback').textContent = `状态刷新失败：${error.message}。已显示的记录可能不是最新状态。`;
    } finally { knowledgeRefreshBusy = false; }
}, 3000);

return {documents, showDocument, originalLink, sqlEvidence};
}
