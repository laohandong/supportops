package io.supportops.integration.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 固定请求撤销指定密钥，不删除历史。 */
@Schema(description = "固定请求撤销指定密钥，不删除历史。")
public record RevokeKeyCommand(@Schema(description = "固定请求 UUID") String requestId,
        @Schema(description = "访问密钥 UUID") String keyId) {}
