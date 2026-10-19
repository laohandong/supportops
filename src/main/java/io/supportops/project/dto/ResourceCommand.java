package io.supportops.project.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 创建或更新企业资源；资源类型由固定接口路径决定。 */
@Schema(description = "资源变更命令；创建时 id 为空，更新时必须提供 expectedVersion")
public record ResourceCommand(
        @Schema(description = "固定请求 UUID", requiredMode = Schema.RequiredMode.REQUIRED) String requestId,
        @Schema(description = "更新资源 UUID；创建时为空") String id,
        @Schema(description = "系统的项目 UUID 或环境的系统 UUID；项目为空") String parentId,
        @Schema(description = "唯一编码，小写字母开头，最多64字符") String code,
        @Schema(description = "资源名称，最多128字符") String name,
        @Schema(description = "系统产品标识，最多64字符") String productKey,
        @Schema(description = "环境预期产品版本，最多30字符") String productVersion,
        @Schema(description = "ACTIVE、DISABLED 或 ARCHIVED；创建默认 ACTIVE") String status,
        @Schema(description = "更新前版本，创建时为空") Integer expectedVersion) {}
