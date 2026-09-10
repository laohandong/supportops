package io.supportops.knowledge.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/** 带版本、位置和原文内容的可引用检索片段。 */
@Schema(name = "KnowledgePassage", description = "带版本、位置和原文内容的可引用检索片段。")
public record KnowledgePassage(
        @Schema(
                        description = "稳定引用编号；新分片为批次 UUID:序号，迁移的旧分片保留原 ID。",
                        example = "11111111-1111-4111-8111-111111111111:1")
                String id,
        @Schema(description = "来源文档编号。", format = "uuid") String documentId,
        @Schema(description = "来源文档标题。") String title,
        @Schema(description = "适用版本或通用标记 *。", example = "2.0") String version,
        @Schema(description = "原文标题、行号或 PDF 页码及分段位置。") String location,
        @Schema(description = "检索到的原始片段正文。") String content,
        @Schema(description = "RRF 排名融合分数，按各召回列表的 1/(60+名次) 相加，不是诊断置信度。", example = "0.032")
                double score,
        @Schema(description = "来源修订 UUID，旧引用可空。") String versionId,
        @Schema(description = "来源分片批次 UUID，旧引用可空。") String batchId,
        @Schema(description = "通过本机接口访问确切修订的原件，相对 URL。") String originalUrl,
        @Schema(description = "本地重排序模型的原始 logits，越大越相关，可为负；非概率，未重排及旧事件为空。") Double rerankScore) {
    /** 兼容已有完整引用，保留原 RRF 分数的解释。 */
    public KnowledgePassage(String id, String documentId, String title, String version,
            String location, String content, double score, String versionId, String batchId, String originalUrl) {
        this(id, documentId, title, version, location, content, score, versionId, batchId, originalUrl, null);
    }
    /** 兼容已经持久化的旧引用。 */
    public KnowledgePassage(
            String id,
            String documentId,
            String title,
            String version,
            String location,
            String content,
            double score) {
        this(id, documentId, title, version, location, content, score, null, null, null);
    }
}
