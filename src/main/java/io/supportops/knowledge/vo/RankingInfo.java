package io.supportops.knowledge.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/** 一次检索的实际排序方式与本地模型证据，不包含模型文件路径。 */
@Schema(description = "实际排序信息；旧事件可以没有此字段，表示原有 RRF 排序。")
public record RankingInfo(
        @Schema(description = "RRF：未启用模型；ONNX：模型精排成功；EMPTY：无候选，未调用模型。",
                allowableValues = {"RRF", "ONNX", "EMPTY"}) String method,
        @Schema(description = "模型与分词文件的 SHA-256 指纹；未调用模型时为空。") String modelId,
        @Schema(description = "进入最终排序的去重候选数量。") int candidateCount,
        @Schema(description = "问题与正文合计最大输入 token 数，超长采用最长序列优先截断；RRF 时为 0。") int maxInputTokens,
        @Schema(description = "排序阶段耗时，单位毫秒，包含等待及首次加载。") long elapsedMs) {}
