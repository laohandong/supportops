// 模型和文档内容都是不可信数据；禁用 HTML 和图片自动加载。
const markdownRenderer = globalThis.markdownit({html: false, breaks: true, linkify: false});
markdownRenderer.disable('image');
markdownRenderer.renderer.rules.table_open = () => '<div class="markdown-table" tabindex="0" role="region" aria-label="表格，可横向滚动"><table>';
markdownRenderer.renderer.rules.table_close = () => '</table></div>';
globalThis.renderMarkdown = text => markdownRenderer.render(text || '');

// 流式阶段保留已成形的顶层块，只解析末尾块；跨块引用在终态统一校准。
globalThis.createMarkdownStream = body => {
    let previous = '', consumed = 0, environment = {}, finished = false;
    let tail = document.createElement('div');
    tail.className = 'markdown-block';
    body.replaceChildren(tail);
    return (text, final = false) => {
        if (text === previous && final === finished) return;
        if (final) {
            body.innerHTML = globalThis.renderMarkdown(text);
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
        const tokens = markdownRenderer.parse(pending, environment);
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
