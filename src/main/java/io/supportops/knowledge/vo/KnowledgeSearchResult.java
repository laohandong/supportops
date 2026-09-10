package io.supportops.knowledge.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** 按版本过滤后返回最多五个片段，召回模式与最终排序方式分别记录。 */
@Schema(name = "KnowledgeSearchResult", description = "按版本过滤后返回最多五个片段；模型分数可为负，不设置相关性阈值。")
public record KnowledgeSearchResult(
        @Schema(
                        description =
                                "实际检索方式：LEXICAL 为关键词；HYBRID 为向量与关键词混合。向量不可用或候选索引不匹配时使用 LEXICAL。",
                        allowableValues = {"LEXICAL", "HYBRID"})
                String mode,
        @Schema(description = "ONNX 时按 rerankScore、RRF 时按 score 降序排列；无适用资料时为空。") List<KnowledgePassage> passages,
        @Schema(
                        description = "本次实际排序证据；旧事件没有此字段。",
                        schemaResolution = Schema.SchemaResolution.ALL_OF_REF)
                RankingInfo ranking) {
    /** 兼容使用原响应构造器的调用方，旧响应不宣称经过模型重排。 */
    public KnowledgeSearchResult(String mode, List<KnowledgePassage> passages) {
        this(mode, passages, null);
    }
}
