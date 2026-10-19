package io.supportops.project.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 管理员设置用户项目成员资格或环境授权。 */
@Schema(description = "管理员设置用户项目成员资格或环境授权。")
public record GrantCommand(
        @Schema(description = "固定请求 UUID", requiredMode = Schema.RequiredMode.REQUIRED) String requestId,
        @Schema(description = "项目或环境 UUID", requiredMode = Schema.RequiredMode.REQUIRED) String resourceId,
        @Schema(description = "用户 UUID", requiredMode = Schema.RequiredMode.REQUIRED) String userId,
        @Schema(description = "true 授权，false 撤销", requiredMode = Schema.RequiredMode.REQUIRED) boolean granted) {}
