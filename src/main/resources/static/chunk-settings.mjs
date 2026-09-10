// 文本分片的界面规则；每片是上限，重叠只发生在同章节或同一 PDF 页内。
export const DEFAULT_CHUNK_OPTIONS = Object.freeze({chunkSize: 100, overlap: 10});

export function validChunkOptions(chunkSize, overlap) {
    return Number.isInteger(chunkSize) && Number.isInteger(overlap)
        && chunkSize >= 100 && chunkSize <= 8000 && overlap >= 0 && chunkSize - overlap >= 10;
}

export function batchChunkOptions(batch) {
    // 历史未知参数用于重新处理时采用当前默认值，不能把迁移占位值伪装成已验证配置。
    return validChunkOptions(batch?.chunkSize, batch?.overlap)
        ? {chunkSize: batch.chunkSize, overlap: batch.overlap} : {...DEFAULT_CHUNK_OPTIONS};
}

export function batchChunkLabel(batch) {
    return validChunkOptions(batch?.chunkSize, batch?.overlap)
        ? `查看分片（上限 ${batch.chunkSize} / 重叠 ${batch.overlap}）`
        : '查看历史分片（参数未记录）';
}
