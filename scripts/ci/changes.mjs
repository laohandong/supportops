import {execFileSync} from 'node:child_process';
import {appendFileSync, readFileSync} from 'node:fs';
import {resolve} from 'node:path';
import {pathToFileURL} from 'node:url';

/** 按实际运行输入划分验证范围；未识别的路径保守进入完整验证。 */
export function classifyFiles(files, forceFull = false) {
    const plan = {files: [...new Set(files)].sort(), javascript: false, java: false, runtime: false, automation: false};
    for (const file of plan.files) {
        if (/^(scripts\/ci\/|src\/test\/ci\/)/.test(file) || file === '.github/workflows/verify.yml') {
            plan.automation = true;
        } else if (/^(?:README|ROADMAP|AGENTS)\.md$/.test(file)
            || /^docs\/.*\.(?:md|png|jpg|jpeg|svg|gif|webp|mp4|srt)$/.test(file)
            || /^docs\/[^/]*(?:evidence|resources)[^/]*\.json$/.test(file)
            || ['LICENSE', 'evaluation/README.md', 'src/main/resources/static/vendor/README.md'].includes(file)) {
            // 内置 Skill 和示例知识的 Markdown 属于运行输入，不能用扩展名免除验证。
        } else if (file.startsWith('src/test/js/')) {
            plan.javascript = true;
        } else if (file.startsWith('src/test/java/')) {
            plan.java = true;
        } else if (file.startsWith('src/main/resources/static/')) {
            plan.javascript = true;
            plan.runtime = true;
        } else if (file.startsWith('src/main/java/')) {
            plan.java = true;
            plan.runtime = true;
        } else if (/^(scripts\/|src\/test\/(?:e2e|recovery)\/|evaluation\/)/.test(file)) {
            plan.javascript = true;
            plan.runtime = true;
        } else {
            forceFull = true;
        }
    }
    if (forceFull) {
        plan.javascript = true;
        plan.java = true;
        plan.runtime = true;
    }
    plan.docsOnly = !plan.javascript && !plan.java && !plan.runtime && !plan.automation;
    return plan;
}

/** 通过参数数组读取 Git，避免分支名、文件名或事件字段成为 shell 指令。 */
function git(root, args) {
    return execFileSync('git', args, {cwd: root, encoding: 'utf8', windowsHide: true, maxBuffer: 16 * 1024 * 1024, stdio: ['ignore', 'pipe', 'pipe']});
}

/** 校验事件中的提交身份，拒绝缺失身份导致的空差异误判。 */
function commitSha(value) {
    if (!/^[a-f0-9]{40,64}$/i.test(value || '') || /^0+$/.test(value)) {
        throw Error('INVALID_COMMIT_SHA');
    }
    return value;
}

/** PR 比较共同祖先到 head；push 比较全部推送提交，首次 push 保守验证全部输入。 */
export function planEvent(root, eventName, event, forceFull = false) {
    let base;
    let head;
    let files;
    if (eventName === 'pull_request') {
        head = commitSha(event.pull_request?.head?.sha);
        const target = commitSha(event.pull_request?.base?.sha);
        base = git(root, ['merge-base', target, head]).trim();
    } else if (eventName === 'push') {
        head = commitSha(event.after);
        if (event.before && /^0+$/.test(event.before)) {
            files = git(root, ['ls-tree', '-r', '--name-only', '-z', head]).split('\0').filter(Boolean);
            forceFull = true;
            base = null;
        } else {
            base = commitSha(event.before);
        }
    } else if (eventName === 'workflow_dispatch') {
        head = git(root, ['rev-parse', 'HEAD']).trim();
        base = git(root, ['rev-parse', 'HEAD^']).trim();
        forceFull ||= event.inputs?.full === true || event.inputs?.full === 'true';
    } else {
        throw Error('UNSUPPORTED_EVENT');
    }
    // 禁用重命名折叠，确保移出代码目录、删除文件和目标目录均参与分类。
    files ??= git(root, ['diff', '--name-only', '--no-renames', '-z', base, head, '--']).split('\0').filter(Boolean);
    return {...classifyFiles(files, forceFull), event: eventName, base, head};
}

/** 汇总必须检查真实成功；不适用的作业跳过不能掩盖应执行作业的失败、取消或缺失。 */
export function assertResults(needs) {
    for (const name of ['changes', 'lightweight']) {
        if (needs[name]?.result !== 'success') {
            throw Error(`REQUIRED_CHECK_NOT_SUCCESSFUL: ${name}`);
        }
    }
    const outputs = needs.changes.outputs;
    for (const name of ['javascript', 'java', 'runtime']) {
        if (!['true', 'false'].includes(outputs?.[name])) {
            throw Error(`INVALID_SCOPE_OUTPUT: ${name}`);
        }
    }
    const expected = {
        javascript: outputs.javascript === 'true',
        java: outputs.java === 'true' || outputs.runtime === 'true'
    };
    for (const [name, required] of Object.entries(expected)) {
        if (needs[name]?.result !== (required ? 'success' : 'skipped')) {
            throw Error(`UNEXPECTED_CHECK_RESULT: ${name}`);
        }
    }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
    try {
        const event = JSON.parse(readFileSync(process.env.GITHUB_EVENT_PATH, 'utf8'));
        const plan = planEvent(process.cwd(), process.env.GITHUB_EVENT_NAME, event);
        if (process.env.GITHUB_OUTPUT) {
            for (const key of ['javascript', 'java', 'runtime', 'automation', 'docsOnly']) {
                appendFileSync(process.env.GITHUB_OUTPUT, `${key}=${plan[key]}\n`);
            }
            appendFileSync(process.env.GITHUB_OUTPUT, `plan=${JSON.stringify(plan)}\n`);
        }
        console.log(JSON.stringify(plan, null, 2));
    } catch (error) {
        console.error(error.message);
        process.exitCode = 1;
    }
}
