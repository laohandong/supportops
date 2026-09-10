import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {validChunkOptions, batchChunkOptions, batchChunkLabel} from '../../main/resources/static/chunk-settings.mjs';

test('100/10 可用，过小窗口、负重叠、小数及不足十字步长均拒绝', () => {
    assert.equal(validChunkOptions(100, 10), true);
    assert.equal(validChunkOptions(750, 100), true);
    for (const [size, overlap] of [[99, 10], [100, -1], [100, 91], [100, 100], [100.5, 10], [8001, 0]]) {
        assert.equal(validChunkOptions(size, overlap), false);
    }
});

test('历史参数未知时显示未知，重新处理采用 100/10', () => {
    const legacy = {chunkSize: null, overlap: null};
    assert.deepEqual(batchChunkOptions(legacy), {chunkSize: 100, overlap: 10});
    assert.equal(batchChunkLabel(legacy), '查看历史分片（参数未记录）');
});

test('已知批次显示并沿用实际参数，而非覆盖为全局默认值', () => {
    const existing = {chunkSize: 300, overlap: 75};
    assert.deepEqual(batchChunkOptions(existing), {chunkSize: 300, overlap: 75});
    assert.equal(batchChunkLabel(existing), '查看分片（上限 300 / 重叠 75）');
});

test('上传页面初始值与历史重处理的默认值一致', async () => {
    const html = await readFile(new URL('../../main/resources/static/index.html', import.meta.url), 'utf8');
    const inputValue = id => Number(new RegExp('<input id="' + id + '"[^>]*value="(\\d+)"').exec(html)?.[1]);
    assert.deepEqual({chunkSize: inputValue('chunk-size'), overlap: inputValue('chunk-overlap')}, batchChunkOptions({}));
});
