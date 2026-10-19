package io.supportops.integration.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/** 可恢复的签发状态，不包含访问密钥原文或摘要。 */
@Schema(description = "可恢复的签发状态，不包含访问密钥原文或摘要。")
public record AccessKeyView(@Schema(description = "密钥 UUID") String id,
        @Schema(description = "应用 UUID") String applicationId,
        @Schema(description = "状态：ACTIVE有效、REVOKED撤销、EXPIRED过期") String status,
        @Schema(description = "过期时间 ISO-8601") String expiresAt,
        @Schema(description = "签发时间 ISO-8601") String createdAt) {}
