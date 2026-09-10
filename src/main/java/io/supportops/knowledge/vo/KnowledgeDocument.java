package io.supportops.knowledge.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/** 文档元数据及最新目标处理批次；异步受理不代表索引已就绪。 */
@Schema(name = "KnowledgeDocument", description = "文档元数据及异步处理状态。最新目标批次和当前可检索发布批次可能不同。")
public record KnowledgeDocument(
        @Schema(description = "文档唯一编号。", format = "uuid") String id,
        @Schema(description = "文档标题。", example = "OrderBridge 2.0 运行手册") String title,
        @Schema(description = "适用产品版本；* 表示通用资料。", example = "2.0") String version,
        @Schema(description = "去除目录路径后的原始文件名。", example = "orderbridge-2.0.md") String filename,
        @Schema(description = "上传时间，ISO 8601。", format = "date-time") String createdAt,
        @Schema(description = "已建索引的基础地址与模型指纹，格式为 baseUrl|model；未建索引为空。更换模型后旧索引可能不再适用。")
                String embeddingKey,
        @Schema(description = "文档分片数量。", example = "8") int chunks,
        @Schema(description = "最新修订编号，旧记录可空。") String versionId,
        @Schema(description = "当前目标处理批次，旧记录可空。") String batchId,
        @Schema(description = "目标批次处理状态。") String processingStatus,
        @Schema(description = "关键词索引状态。") String textStatus,
        @Schema(description = "向量任务状态；只有 SUCCEEDED 且配置匹配才可用于向量检索。") String vectorStatus) {
    /** 兼容旧元数据构造；无原件的旧记录不能虚构完成状态。 */
    public KnowledgeDocument(
            String id,
            String title,
            String version,
            String filename,
            String createdAt,
            String embeddingKey,
            int chunks) {
        this(
                id,
                title,
                version,
                filename,
                createdAt,
                embeddingKey,
                chunks,
                null,
                null,
                "LEGACY",
                "PENDING",
                "PENDING");
    }
}
