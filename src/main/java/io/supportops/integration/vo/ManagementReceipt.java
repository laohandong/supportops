package io.supportops.integration.vo;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;

/** 管理端查询原固定请求的安全回执，签发秘密永远不回放。 */
@Schema(description = "管理端查询原固定请求的安全回执，签发秘密永远不回放。")
public record ManagementReceipt(@Schema(description = "原请求 UUID") String requestId,
        @Schema(description = "固定管理操作名称") String operation,
        @Schema(description = "该操作的公开响应结构") JsonNode receipt,
        @Schema(description = "提交时间") String createdAt) {}
