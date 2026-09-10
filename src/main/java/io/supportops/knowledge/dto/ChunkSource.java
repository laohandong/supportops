package io.supportops.knowledge.dto;

/**
 * MySQL 为 ES 提供的固定结构投影；关联元数据从修订和原件表连接获取。
 *
 * @param versionId 不可变资料修订编号，与产品适用版本不同
 * @param batchId 产生该分片的解析批次，用于当前发布代过滤
 * @param productVersion 适用产品版本或通用标记 *
 * @param chunkIndex 同批次内从 1 开始的分片顺序
 * @param contentHash 正文 UTF-8 字节的 SHA-256，用于补偿校验
 * @param pageStart PDF 起始页，一基；Markdown 为空
 * @param pageEnd PDF 结束页，一基且含边界；Markdown 为空
 * @param lineStart Markdown 来源起始行，一基；PDF 为空
 * @param lineEnd Markdown 来源结束行，一基且含边界；PDF 为空
 * @param charStart 来源页或章节内的码点偏移，零基
 * @param charEnd 来源区块内结束码点偏移，不含边界
 * @param charCount 片段实际 Unicode 码点数
 */
public record ChunkSource(
        String id,
        String documentId,
        String versionId,
        String batchId,
        String fileId,
        String title,
        String filename,
        String fileType,
        String productVersion,
        int revision,
        int chunkIndex,
        String content,
        String contentHash,
        String heading,
        String headingPath,
        String location,
        Integer pageStart,
        Integer pageEnd,
        Integer lineStart,
        Integer lineEnd,
        Integer charStart,
        Integer charEnd,
        int charCount) {}
