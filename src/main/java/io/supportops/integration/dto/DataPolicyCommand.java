package io.supportops.integration.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 管理员分别批准对话和向量资料外发。 */
@Schema(description = "管理员分别批准对话和向量资料外发。")
public record DataPolicyCommand(@Schema(description = "固定请求 UUID") String requestId,
        @Schema(description = "系统 UUID") String systemId,
        @Schema(description = "明确批准对话外发") boolean chatAllowed,
        @Schema(description = "明确批准向量外发") boolean embeddingAllowed,
        @Schema(description = "读取前策略版本；尚未保存时0") int expectedVersion) {}
