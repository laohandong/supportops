// 模型和文档内容都是不可信数据；禁用 HTML 和图片自动加载。
const markdownRenderer = globalThis.markdownit({html: false, breaks: true, linkify: false});
markdownRenderer.disable('image');
markdownRenderer.renderer.rules.table_open = () => '<div class="markdown-table" tabindex="0" role="region" aria-label="表格，可横向滚动"><table>';
markdownRenderer.renderer.rules.table_close = () => '</table></div>';
globalThis.renderMarkdown = text => markdownRenderer.render(text || '');

// 仅处理诊断的展示副本；原始证据、引用片段及数据库字段仍保留来源时间。
globalThis.formatDiagnosisTimes = text => {
    let fence = '';
    return (text || '').split('\n').map(line => {
        // 引用块中的代码围栏同样是原始日志；仅识别结构，不删除展示文本的引用标记。
        const content = line.replace(/^(?: {0,3}>[ \t]?)+/, '');
        const marker = /^ {0,3}(`{3,}|~{3,})([\s\S]*)$/.exec(content);
        if (marker) {
            if (!fence) fence = marker[1];
            else if (marker[1][0] === fence[0] && marker[1].length >= fence.length && !marker[2].trim()) fence = '';
            return line;
        }
        if (fence || /^(?: {4}|\t)/.test(content)) return line;
        return line.replace(/(?<![A-Za-z0-9_/?=&%#.+-])\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})(?![A-Za-z0-9_/?=&%#+-])/g, value => {
            // Date 会把 2 月 30 日自动进位；先核验原始日期，非法值不得伪装为有效时间。
            const local = new Date(value.slice(0, 19) + 'Z');
            if (!Number.isFinite(local.getTime()) || local.toISOString().slice(0, 19) !== value.slice(0, 19)) return value;
            const offset = /([+-])(\d{2}):(\d{2})$/.exec(value);
            if (offset && (+offset[2] > 18 || +offset[3] > 59 || (+offset[2] === 18 && +offset[3] !== 0))) return value;
            const instant = new Date(value);
            if (!Number.isFinite(instant.getTime())) return value;
            return new Date(instant.getTime() + 8 * 3600000).toISOString().slice(0, 19).replace('T', ' ') + '（北京时间）';
        });
    }).join('\n');
};
globalThis.renderDiagnosisMarkdown = text => markdownRenderer.render(globalThis.formatDiagnosisTimes(text));

// 流式阶段保留已成形的顶层块，只解析末尾块；跨块引用在终态统一校准。
globalThis.createMarkdownStream = body => {
    let previous = '', consumed = 0, environment = {}, finished = false;
    let tail = document.createElement('div');
    tail.className = 'markdown-block';
    body.replaceChildren(tail);
    return (text, final = false) => {
        if (text === previous && final === finished) return;
        if (final) {
            body.innerHTML = globalThis.renderDiagnosisMarkdown(text);
            previous = text;
            finished = true;
            return;
        }
        if (finished || !text.startsWith(previous)) {
            consumed = 0;
            environment = {};
            tail = document.createElement('div');
            tail.className = 'markdown-block';
            body.replaceChildren(tail);
        }
        previous = text;
        finished = false;
        const pending = text.slice(consumed);
        const tokens = markdownRenderer.parse(globalThis.formatDiagnosisTimes(pending), environment);
        const starts = [];
        tokens.forEach((token, index) => {
            if (token.level === 0 && token.map && token.nesting !== -1) starts.push(index);
        });
        for (let block = 0; block < starts.length - 1; block++) {
            const node = document.createElement('div');
            node.className = 'markdown-block';
            node.innerHTML = markdownRenderer.renderer.render(tokens.slice(starts[block], starts[block + 1]), markdownRenderer.options, environment);
            body.insertBefore(node, tail);
        }
        const last = starts.at(-1) ?? 0;
        tail.innerHTML = markdownRenderer.renderer.render(tokens.slice(last), markdownRenderer.options, environment);
        if (starts.length > 1) {
            const lines = pending.split('\n');
            consumed += lines.slice(0, tokens[last].map[0]).reduce((length, line) => length + line.length + 1, 0);
        }
    };
};
