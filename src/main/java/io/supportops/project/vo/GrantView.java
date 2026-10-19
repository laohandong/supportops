package io.supportops.project.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/** 授权变更的安全回执。 */
@Schema(description = "授权变更的安全回执。")
public record GrantView(
        @Schema(description = "项目或环境 UUID") String resourceId,
        @Schema(description = "用户 UUID") String userId,
        @Schema(description = "当前是否授权") boolean granted) {}
