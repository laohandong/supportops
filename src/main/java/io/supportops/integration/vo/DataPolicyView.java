package io.supportops.integration.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/** 独立系统资料策略；默认禁止外发不影响关键词和文档管理。 */
@Schema(description = "独立系统资料策略；默认禁止外发不影响关键词和文档管理。")
public record DataPolicyView(@Schema(description = "系统 UUID") String systemId,
        @Schema(description = "允许资料与约束进入对话模型") boolean chatAllowed,
        @Schema(description = "允许资料和问题发送向量服务") boolean embeddingAllowed,
        @Schema(description = "版本；未保存0") int version) {}
