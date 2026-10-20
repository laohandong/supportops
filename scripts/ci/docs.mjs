import {execFileSync} from 'node:child_process';
import {readFile, realpath, stat} from 'node:fs/promises';
import {dirname, isAbsolute, relative, resolve, sep} from 'node:path';
import {pathToFileURL} from 'node:url';

/** 去除代码示例，避免把示例中的 Markdown 语法当成真实链接。 */
function prose(text) {
    return text.replace(/^(`{3,}|~{3,})[^\n]*\n[\s\S]*?^\1\s*$/gm, '');
}

/** 提取常用行内链接及引用式链接；不请求网络，也不执行示例。 */
function links(text) {
    const content = prose(text).replace(/`[^`\n]+`/g, '');
    const targets = [...content.matchAll(/!?\[[^\]\n]*\]\((<[^>]+>|[^)\s]+)(?:\s+"[^"]*")?\)/g)].map(match => match[1]);
    const definitions = new Map([...content.matchAll(/^\s{0,3}\[([^\]]+)\]:\s*(<[^>]+>|\S+)/gm)].map(match => [match[1].toLowerCase(), match[2]]));
    for (const match of content.matchAll(/!?\[([^\]\n]+)\](?:\[([^\]\n]*)\])?(?!\()/g)) {
        const target = definitions.get((match[2] || match[1]).toLowerCase());
        if (target) {
            targets.push(target);
        }
    }
    return targets.map(target => target.replace(/^<|>$/g, ''));
}

/** 为标题生成 GitHub 常用锚点，并保留同名标题后缀和显式 HTML id。 */
function anchors(text) {
    const result = new Set();
    const counts = new Map();
    for (const match of prose(text).matchAll(/^#{1,6}\s+(.+?)\s*#*\r?$/gm)) {
        const slug = match[1].replace(/<[^>]+>/g, '').replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')
            .replace(/[`*~]/g, '').toLowerCase().replace(/[^\p{Letter}\p{Number}\p{Mark}_\-\s]/gu, '').replace(/ /g, '-');
        const count = counts.get(slug) || 0;
        counts.set(slug, count + 1);
        result.add(slug + (count ? `-${count}` : ''));
    }
    for (const match of text.matchAll(/\b(?:id|name)=["']([^"']+)["']/g)) {
        result.add(match[1]);
    }
    return result;
}

/** 将仓库内相对链接解析为文件及锚点；拒绝越界和本机绝对路径。 */
function localTarget(file, url) {
    if (/^(?:https?|mailto|tel):/i.test(url) || url.startsWith('//')) {
        return null;
    }
    if (/^[a-z][a-z\d+.-]*:/i.test(url) || url.startsWith('/') || url.includes('\\')) {
        throw Error('NON_PORTABLE_LINK');
    }
    const [path, anchor] = url.split('#', 2);
    const target = relative('.', resolve(dirname(file), decodeURIComponent(path.split('?')[0]) || file.split('/').at(-1))).split(sep).join('/');
    if (target === '..' || target.startsWith('../') || isAbsolute(target)) {
        throw Error('LINK_OUTSIDE_REPOSITORY');
    }
    return {path: target, anchor: anchor ? decodeURIComponent(anchor) : null};
}

/** 只检查变更文档及引用变更目标的链接，覆盖删除/标题变更，避免重审全部历史内容。 */
export async function checkDocs(root, changedFiles, trackedFiles) {
    const changed = new Set(changedFiles);
    const tracked = new Set(trackedFiles);
    const issues = [];
    let checkedLinks = 0;
    let checkedDocuments = 0;
    const canonicalRoot = await realpath(root);
    for (const file of trackedFiles.filter(path => path.endsWith('.md'))) {
        const text = await readFile(resolve(root, file), 'utf8');
        if (changed.has(file)) {
            checkedDocuments++;
        }
        for (const url of links(text)) {
            let selected = changed.has(file);
            try {
                const target = localTarget(file, url);
                selected ||= target !== null && changed.has(target.path);
                if (!target || !selected) {
                    continue;
                }
                checkedLinks++;
                const delivered = tracked.has(target.path) || trackedFiles.some(path => path.startsWith(target.path + '/'));
                if (!delivered) {
                    throw Error('TARGET_NOT_TRACKED');
                }
                const absolute = resolve(root, target.path);
                const canonical = await realpath(absolute);
                const inside = relative(canonicalRoot, canonical);
                if (inside === '..' || inside.startsWith('..' + sep) || isAbsolute(inside)) {
                    throw Error('LINK_OUTSIDE_REPOSITORY');
                }
                if (target.anchor && target.path.endsWith('.md') && !(await stat(absolute)).isDirectory()) {
                    if (!anchors(await readFile(absolute, 'utf8')).has(target.anchor)) {
                        throw Error('MISSING_ANCHOR');
                    }
                }
            } catch (error) {
                if (selected) {
                    issues.push({file, url, error: error.code || error.message});
                }
            }
        }
    }
    for (const file of changedFiles.filter(path => path.endsWith('.json') && tracked.has(path) && path.startsWith('docs/'))) {
        try {
            JSON.parse(await readFile(resolve(root, file), 'utf8'));
        } catch (error) {
            issues.push({file, error: 'INVALID_JSON'});
        }
    }
    return {checkedDocuments, checkedLinks, issues};
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
    try {
        const plan = JSON.parse(process.env.CI_PLAN);
        const tracked = execFileSync('git', ['ls-files', '-z'], {encoding: 'utf8', windowsHide: true}).split('\0').filter(Boolean);
        const result = await checkDocs(process.cwd(), plan.files, tracked);
        console.log(JSON.stringify(result, null, 2));
        if (result.issues.length) {
            process.exitCode = 1;
        }
    } catch (error) {
        console.error(error.message);
        process.exitCode = 1;
    }
}
