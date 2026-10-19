package io.supportops.integration.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 签发与轮换不可恢复应用访问密钥。 */
@Schema(description = "签发与轮换不可恢复应用访问密钥。")
public record AccessKeyCommand(
        @Schema(description = "固定请求 UUID", requiredMode = Schema.RequiredMode.REQUIRED) String requestId,
        @Schema(description = "应用 UUID", requiredMode = Schema.RequiredMode.REQUIRED) String applicationId,
        @Schema(description = "有效天数1至90，默认30") Integer expiresInDays,
        @Schema(description = "是否轮换；旧活动密钥最多保留24小时") boolean rotate) {}
