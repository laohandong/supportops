package io.supportops.integration.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 测试或启用指定连接修订。 */
@Schema(description = "测试或启用指定连接修订。")
public record ConnectorAction(
        @Schema(description = "固定请求 UUID", requiredMode = Schema.RequiredMode.REQUIRED) String requestId,
        @Schema(description = "连接修订 UUID", requiredMode = Schema.RequiredMode.REQUIRED) String revisionId,
        @Schema(description = "启用时的环境旧版本；测试时为空") Integer expectedVersion) {}
