// 用量图表只使用服务端快照；不补造用量、不对无诊断时段推算平均值。
const usagePalette = {input: '#24634f', output: '#74a9bf', cumulative: '#315c9b', average: '#9a672c', grid: '#edf0ee', text: '#62736b'};
const usageNumber = value => Number(value).toLocaleString('zh-CN', {maximumFractionDigits: 1});
const totalTokens = row => row.inputTokens + row.outputTokens;

export function usageSeries(timeline) {
    let cumulative = 0;
    return timeline.map(row => {
        cumulative += totalTokens(row);
        return {...row, total: totalTokens(row), cumulative, average: row.runs ? totalTokens(row) / row.runs : null};
    });
}

export function usageShortNumber(value) {
    if (Math.abs(value) >= 1000000) return usageNumber(value / 1000000) + 'M';
    if (Math.abs(value) >= 1000) return usageNumber(value / 1000) + 'k';
    return usageNumber(value);
}

/** 创建快照的图表与精确明细，返回销毁函数供下一次查询释放画布。 */
export function renderUsageDashboard(root, data, {el, onSession, Chart}) {
    const charts = [], summary = data.summary;
    const timeline = usageSeries(data.timeline || []);
    const top = data.topSessions || [];
    const total = totalTokens(summary);
    const metric = (label, value, note) => {
        const item = el('div', undefined, 'usage-stat');
        item.append(el('dt', label), el('dd', value), el('p', note));
        return item;
    };
    const stats = el('dl', undefined, 'usage-stats');
    stats.append(
        metric('已报告总 Token', usageNumber(total), '输入 ' + usageNumber(summary.inputTokens) + ' / 输出 ' + usageNumber(summary.outputTokens)),
        metric('对话与诊断', usageNumber(summary.sessions) + ' / ' + usageNumber(summary.runs), '个会话 / 轮诊断'),
        metric('平均每轮 Token', summary.runs ? usageNumber(total / summary.runs) : '—', '已报告总量 ÷ 全部诊断轮数'),
        metric('已完成诊断', usageNumber(summary.completed) + ' / ' + usageNumber(summary.runs), '执行完成不等于回答正确')
    );
    root.append(stats);
    const health = el('div', undefined, summary.unreported ? 'usage-coverage incomplete' : 'usage-coverage');
    health.append(el('span', summary.unreported ? '计量有缺口' : '计量说明', 'usage-coverage-label'),
        el('span', summary.unreported ? usageNumber(summary.unreported) + ' 轮尚无用量报告，图中数值可能低于实际消耗。' : '已报告 Token 用于分析消耗；不等同于供应商账单。'));
    root.append(health);
    if (!summary.runs) {
        const empty = el('div', undefined, 'usage-empty');
        empty.append(el('h2', '这个时间段还没有诊断记录'), el('p', '选择其他日期，或完成一次诊断后更新图表。空白范围不会被显示为已有消耗。'));
        root.append(empty);
        return () => {};
    }
    const figureGrid = el('div', undefined, 'usage-figure-grid');
    root.append(figureGrid);

    // 画布的可访问文本、说明与下方精确表格共同表达图表信息。
    function figure(title, description, legend, className = '') {
        const panel = el('section', undefined, 'usage-figure ' + className);
        const heading = el('div', undefined, 'usage-figure-heading');
        const text = el('div'); text.append(el('h2', title), el('p', description)); heading.append(text);
        panel.append(heading);
        const legendNode = el('div', undefined, 'usage-legend');
        legend.forEach(([name, color]) => {
            const entry = el('span'); const mark = el('i'); mark.style.backgroundColor = color;
            mark.setAttribute('aria-hidden', 'true'); entry.append(mark, el('span', name)); legendNode.append(entry);
        });
        panel.append(legendNode);
        const body = el('div', undefined, 'usage-canvas-wrap');
        const canvas = el('canvas'); canvas.setAttribute('role', 'img'); canvas.setAttribute('aria-label', title + '；精确数值见下方分时明细或会话排行');
        body.append(canvas); panel.append(body); figureGrid.append(panel);
        return {panel, canvas, body};
    }
    function options(kind, format = usageShortNumber) {
        return {
            responsive: true, maintainAspectRatio: false,
            animation: false,
            interaction: {mode: 'index', intersect: false},
            plugins: {
                legend: {display: false},
                tooltip: {
                    backgroundColor: '#233d32', titleColor: '#fff', bodyColor: '#fff', padding: 12, cornerRadius: 6,
                    callbacks: {
                        title: items => items[0] ? timeline[items[0].dataIndex].groupId.replace('T', ' ') + ' UTC' : '',
                        label: context => context.dataset.label + '：' + usageNumber(context.parsed.y) + ' Token'
                    }
                }
            },
            scales: {
                x: {grid: {display: false}, border: {display: false}, ticks: {color: usagePalette.text, maxRotation: 0, autoSkip: true, maxTicksLimit: 6, font: {size: 11}}},
                y: {beginAtZero: true, border: {display: false}, grid: {color: usagePalette.grid}, ticks: {color: usagePalette.text, maxTicksLimit: 5, padding: 8, callback: format, font: {size: 11}}}
            },
            elements: {line: {borderWidth: 2, tension: 0}, point: {radius: timeline.length <= 31 ? 2 : 0, hoverRadius: 5}, bar: {borderRadius: 3}},
            layout: {padding: {top: 4, right: 8}}
        };
    }
    function draw(target, config) {
        if (!Chart) {
            target.body.replaceChildren(el('p', '图表组件未能加载，请刷新页面。精确数值仍可在下方查看。', 'usage-chart-error'));
            return;
        }
        try { charts.push(new Chart(target.canvas, config)); }
        catch (error) { target.body.replaceChildren(el('p', '图表暂时无法绘制，请查看下方数值明细。', 'usage-chart-error')); }
    }
    const labels = timeline.map(row => data.granularity === 'hour' ? [row.groupId.slice(5, 10), row.groupId.slice(11) + ':00'] : row.groupId.slice(5));
    const trend = figure('Token 用量分布', '每个时段的输入与输出，查看用量高峰出现在哪里。', [['输入 Token', usagePalette.input], ['输出 Token', usagePalette.output]], 'usage-figure-wide');
    const trendOptions = options('bar'); trendOptions.scales.x.stacked = true; trendOptions.scales.y.stacked = true;
    trendOptions.plugins.tooltip.callbacks.footer = items => items.length ? '时段合计：' + usageNumber(timeline[items[0].dataIndex].total) + ' Token' : '';
    draw(trend, {type: 'bar', data: {labels, datasets: [
        {label: '输入', data: timeline.map(row => row.inputTokens), backgroundColor: usagePalette.input, maxBarThickness: 38},
        {label: '输出', data: timeline.map(row => row.outputTokens), backgroundColor: usagePalette.output, maxBarThickness: 38}
    ]}, options: trendOptions});
    const peak = timeline.reduce((best, row) => row.total > best.total ? row : best, timeline[0]);
    trend.panel.append(el('p', '时段峰值 ' + usageNumber(peak.total) + ' Token · ' + peak.groupId.replace('T', ' ') + (data.granularity === 'hour' ? ':00' : '') + ' UTC', 'usage-chart-footnote'));

    const accumulated = figure('累计用量', '从所选范围起点累计，观察消耗增长的节奏。', [['累计总 Token', usagePalette.cumulative]]);
    draw(accumulated, {type: 'line', data: {labels, datasets: [{label: '累计', data: timeline.map(row => row.cumulative), borderColor: usagePalette.cumulative, backgroundColor: '#315c9b0d', fill: true}]}, options: options('line')});
    accumulated.panel.append(el('p', '期末累计 ' + usageNumber(total) + ' Token', 'usage-chart-footnote'));

    const rank = figure('高消耗会话', '前 5 个会话。点击柱形或下方会话名称回看问答。', [['输入 Token', usagePalette.input], ['输出 Token', usagePalette.output]], 'usage-figure-wide');
    const leaders = top.slice(0, 5);
    const rankOptions = options('bar'); rankOptions.indexAxis = 'y'; rankOptions.interaction.axis = 'y'; rankOptions.scales.x.stacked = true; rankOptions.scales.y.stacked = true;
    rankOptions.scales.x.grid = {color: usagePalette.grid}; rankOptions.scales.x.ticks.callback = usageShortNumber;
    rankOptions.scales.y.grid = {display: false}; delete rankOptions.scales.y.ticks.callback;
    rankOptions.plugins.tooltip.callbacks = {title: items => items.length ? (leaders[items[0].dataIndex].question.match(/.{1,30}/gu) || []).slice(0, 6) : '', label: context => context.dataset.label + '：' + usageNumber(context.parsed.x) + ' Token'};
    rankOptions.onClick = (event, elements) => { if (elements.length) onSession(leaders[elements[0].index].groupId, 0); };
    draw(rank, {type: 'bar', data: {labels: leaders.map((item, index) => '#' + (index + 1)), datasets: [
        {label: '输入', data: leaders.map(row => row.inputTokens), backgroundColor: usagePalette.input, maxBarThickness: 23},
        {label: '输出', data: leaders.map(row => row.outputTokens), backgroundColor: usagePalette.output, maxBarThickness: 23}
    ]}, options: rankOptions});
    const rankLinks = el('div', undefined, 'usage-rank-links');
    leaders.forEach((item, index) => { const button = el('button', '#' + (index + 1) + ' ' + item.question); button.type = 'button'; button.title = item.question; button.addEventListener('click', () => onSession(item.groupId, 0)); rankLinks.append(button); });
    rank.panel.append(rankLinks);

    const average = figure('每轮平均消耗', '辨别单轮成本的变化。无诊断的时段保持留空。', [['已报告 Token / 轮', usagePalette.average]]);
    draw(average, {type: 'line', data: {labels, datasets: [{label: '每轮平均', data: timeline.map(row => row.average), borderColor: usagePalette.average, backgroundColor: usagePalette.average, spanGaps: false}]}, options: options('line')});
    average.panel.append(el('p', '用量报告缺失会低估平均值；结合会话内容判断原因。', 'usage-chart-footnote'));

    function table(headers, rows) {
        const wrap = el('div', undefined, 'usage-table-wrap'), tableNode = el('table', undefined, 'usage-table');
        const head = el('thead'), headRow = el('tr');
        headers.forEach(label => { const th = el('th', label); th.scope = 'col'; headRow.append(th); }); head.append(headRow);
        const body = el('tbody');
        rows.forEach(cells => { const row = el('tr'); cells.forEach(value => { const cell = el('td'); cell.append(typeof value === 'object' ? value : document.createTextNode(String(value))); row.append(cell); }); body.append(row); });
        tableNode.append(head, body); wrap.append(tableNode); return wrap;
    }
    const ranking = el('section', undefined, 'usage-ranking');
    const rankHeading = el('div', undefined, 'usage-section-heading');
    const headingText = el('div'); headingText.append(el('h2', '会话用量排行'), el('p', '按所选日期内的总 Token 排序 · 最多 20 个会话'));
    rankHeading.append(headingText, el('span', top.length + ' 个会话', 'usage-count')); ranking.append(rankHeading);
    ranking.append(table(['排名', '会话 / 问题示例', '总 Token', '输入 / 输出', '诊断 / 完成', '未报告', '操作'], top.map((item, index) => {
        const question = el('div', undefined, 'usage-question'); const link = el('button', item.question, 'usage-question-link'); link.addEventListener('click', () => onSession(item.groupId, 0));
        question.append(link, el('small', item.groupId));
        const amount = el('div', undefined, 'usage-amount'); amount.append(el('strong', usageNumber(totalTokens(item))));
        const track = el('span', undefined, 'usage-rank-track'), fill = el('i'); fill.style.width = (top[0] && totalTokens(top[0]) ? totalTokens(item) / totalTokens(top[0]) * 100 : 0) + '%'; track.append(fill); amount.append(track);
        const open = el('button', '回看问答', 'usage-open-button'); open.addEventListener('click', () => onSession(item.groupId, 0));
        return [String(index + 1).padStart(2, '0'), question, amount, usageNumber(item.inputTokens) + ' / ' + usageNumber(item.outputTokens), item.runs + ' / ' + item.completed, item.unreported, open];
    })));
    root.append(ranking);
    const details = el('details', undefined, 'usage-time-details');
    details.append(el('summary', '分时数据明细 · ' + timeline.length + ' 个时段'));
    details.append(table(['时间（UTC）', '输入 Token', '输出 Token', '总 Token', '累计 Token', '平均 / 轮', '轮数', '未报告'], timeline.map(row => [row.groupId.replace('T', ' ') + (data.granularity === 'hour' ? ':00' : ''), usageNumber(row.inputTokens), usageNumber(row.outputTokens), usageNumber(row.total), usageNumber(row.cumulative), row.average === null ? '—' : usageNumber(row.average), row.runs, row.unreported])));
    root.append(details);
    return () => charts.forEach(chart => chart.destroy());
}
