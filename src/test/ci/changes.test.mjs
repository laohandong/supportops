import test from 'node:test';
import assert from 'node:assert/strict';
import {rename} from 'node:fs/promises';
import {join} from 'node:path';
import {assertResults, classifyFiles, planEvent} from '../../../scripts/ci/changes.mjs';
import {commit, fixture, git, put} from './helpers.mjs';

test('文档和只供阅读的证据不启动应用验证，运行 Markdown 与未知输入不能免测', () => {
    assert.equal(classifyFiles(['README.md', 'docs/api.md', 'docs/github-validation-evidence-20261019.json']).docsOnly, true);
    for (const file of ['src/main/resources/skills/diagnose/SKILL.md', 'examples/knowledge/orders.md', 'new-runtime-input.bin', 'pom.xml', 'package-lock.json']) {
        const plan = classifyFiles([file]);
        assert.equal(plan.java && plan.javascript && plan.runtime, true, file);
    }
});

test('代码、测试和 CI 修改按影响选择，混合文档不缩小验证范围', () => {
    const scopes = files => {
        const {java, javascript, runtime, automation} = classifyFiles(files);
        return {java, javascript, runtime, automation};
    };
    assert.deepEqual(scopes(['README.md', 'src/test/java/ExampleTest.java']), {java: true, javascript: false, runtime: false, automation: false});
    assert.deepEqual(scopes(['src/test/js/example.test.mjs']), {java: false, javascript: true, runtime: false, automation: false});
    assert.deepEqual(scopes(['src/main/resources/static/app.js']), {java: false, javascript: true, runtime: true, automation: false});
    assert.deepEqual(scopes(['src/main/java/Example.java']), {java: true, javascript: false, runtime: true, automation: false});
    assert.deepEqual(scopes(['.github/workflows/verify.yml', 'scripts/ci/changes.mjs']), {java: false, javascript: false, runtime: false, automation: true});
});

test('push 覆盖全部提交，保留带空格和换行的路径，代码移入文档目录仍验证删除影响', async context => {
    const root = await fixture(context);
    git(root, 'init', '--initial-branch=main');
    await put(root, 'README.md', '# 初始');
    const base = commit(root);
    await put(root, 'src/main/java/Example.java', 'class Example {}');
    commit(root);
    const unusualPath = process.platform === 'win32' ? 'docs/with space 文档.md' : 'docs/with space\nand newline.md';
    await put(root, unusualPath, '# 说明');
    const head = commit(root);
    const plan = planEvent(root, 'push', {before: base, after: head});
    assert.equal(plan.java && plan.runtime, true);
    assert.equal(plan.files.includes(unusualPath), true);
    await rename(join(root, 'src/main/java/Example.java'), join(root, 'docs/Example.md'));
    const renamed = commit(root);
    const moved = planEvent(root, 'push', {before: head, after: renamed});
    assert.equal(moved.java && moved.runtime, true);
    assert.equal(moved.files.includes('src/main/java/Example.java'), true);
    assert.equal(moved.files.includes('docs/Example.md'), true);
});

test('PR 使用共同祖先比较，目标分支推进不混入 PR 自身差异', async context => {
    const root = await fixture(context);
    git(root, 'init', '--initial-branch=main');
    await put(root, 'README.md', '# 初始');
    const ancestor = commit(root);
    git(root, 'switch', '-c', 'docs-change');
    await put(root, 'README.md', '# 更新说明');
    const head = commit(root);
    git(root, 'switch', 'main');
    await put(root, 'src/main/java/Other.java', 'class Other {}');
    const base = commit(root);
    const plan = planEvent(root, 'pull_request', {pull_request: {base: {sha: base}, head: {sha: head}}});
    assert.equal(plan.base, ancestor);
    assert.equal(plan.docsOnly, true);
    assert.deepEqual(plan.files, ['README.md']);
});

test('首次 push 和显式完整回归保守执行，缺失或无效基准不能误判为文档', async context => {
    const root = await fixture(context);
    git(root, 'init', '--initial-branch=main');
    await put(root, 'README.md', '# 初始');
    const base = commit(root);
    await put(root, 'README.md', '# 新版本');
    const head = commit(root);
    assert.equal(planEvent(root, 'push', {before: '0'.repeat(40), after: head}).runtime, true);
    assert.equal(planEvent(root, 'workflow_dispatch', {inputs: {full: 'true'}}).java, true);
    assert.equal(planEvent(root, 'push', {before: base, after: head}).docsOnly, true);
    assert.throws(() => planEvent(root, 'push', {before: 'f'.repeat(40), after: head}));
    assert.throws(() => planEvent(root, 'push', {before: '--bad-option', after: head}), /INVALID_COMMIT_SHA/);
    assert.throws(() => planEvent(root, 'pull_request', {}), /INVALID_COMMIT_SHA/);
});

test('汇总接受文档作业的预期跳过，任何应执行作业失败、取消、跳过或缺失均失败', () => {
    const needs = {
        changes: {result: 'success', outputs: {java: 'false', javascript: 'false', runtime: 'false'}},
        lightweight: {result: 'success'}, javascript: {result: 'skipped'}, java: {result: 'skipped'}
    };
    assert.doesNotThrow(() => assertResults(needs));
    needs.changes.outputs.runtime = 'true';
    for (const result of ['skipped', 'failure', 'cancelled', undefined]) {
        needs.java.result = result;
        assert.throws(() => assertResults(needs), /UNEXPECTED_CHECK_RESULT/);
    }
    needs.java.result = 'success';
    assert.doesNotThrow(() => assertResults(needs));
    needs.lightweight.result = 'failure';
    assert.throws(() => assertResults(needs), /REQUIRED_CHECK_NOT_SUCCESSFUL/);
    needs.lightweight.result = 'success';
    delete needs.changes.outputs.java;
    assert.throws(() => assertResults(needs), /INVALID_SCOPE_OUTPUT/);
});
