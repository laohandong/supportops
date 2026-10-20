import test from 'node:test';
import assert from 'node:assert/strict';
import {checkDocs} from '../../../scripts/ci/docs.mjs';
import {fixture, put} from './helpers.mjs';

test('核对中文、重复标题、内联代码标题、引用式链接及文档内锚点，忽略代码示例', async context => {
    const root = await fixture(context);
    await put(root, 'README.md', '# 导航\n[接口](docs/api.md#接口)\n[再次](docs/api.md#接口-1)\n[命令][cmd]\n[cmd]: docs/api.md#执行-verify\n[本页](#导航)\n```md\n[示例](not-a-file)\n```\n');
    await put(root, 'docs/api.md', '# 接口\n# 接口\n## 执行 `verify`\n');
    const result = await checkDocs(root, ['README.md'], ['README.md', 'docs/api.md']);
    assert.deepEqual(result.issues, []);
    assert.ok(result.checkedLinks >= 4);
});

test('删除被引用文件和改动标题会检查未修改文档中的关联链接', async context => {
    const root = await fixture(context);
    await put(root, 'README.md', '[旧接口](docs/api.md#旧接口)\n');
    const deleted = await checkDocs(root, ['docs/api.md'], ['README.md']);
    assert.equal(deleted.issues[0].error, 'TARGET_NOT_TRACKED');
    await put(root, 'docs/api.md', '# 新接口\n');
    const renamed = await checkDocs(root, ['docs/api.md'], ['README.md', 'docs/api.md']);
    assert.equal(renamed.issues[0].error, 'MISSING_ANCHOR');
});

test('本地存在但未交付的目标不能通过，越界链接不能读取仓库外文件', async context => {
    const root = await fixture(context);
    await put(root, 'README.md', '[本地](private.md)\n[越界](../outside.md)\n');
    await put(root, 'private.md', '# 不交付\n');
    const result = await checkDocs(root, ['README.md'], ['README.md']);
    assert.deepEqual(result.issues.map(issue => issue.error), ['TARGET_NOT_TRACKED', 'LINK_OUTSIDE_REPOSITORY']);
});

test('变更验收 JSON 需要有效语法，无关历史断链不扩大为全仓库重审', async context => {
    const root = await fixture(context);
    await put(root, 'README.md', '[旧记录](missing.md)\n');
    await put(root, 'docs/current.md', '# 当前\n');
    await put(root, 'docs/evidence.json', '{invalid');
    const tracked = ['README.md', 'docs/current.md', 'docs/evidence.json'];
    assert.deepEqual((await checkDocs(root, ['docs/current.md'], tracked)).issues, []);
    assert.equal((await checkDocs(root, ['docs/evidence.json'], tracked)).issues[0].error, 'INVALID_JSON');
});
