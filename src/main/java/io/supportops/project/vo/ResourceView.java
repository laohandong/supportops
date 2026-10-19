package io.supportops.project.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/** 企业资源公开描述，不包含连接地址与凭据。 */
@Schema(description = "企业资源公开描述，不包含连接地址与凭据。")
public record ResourceView(
        @Schema(description = "资源 UUID") String id,
        @Schema(description = "PROJECT、SYSTEM 或 ENVIRONMENT") String kind,
        @Schema(description = "上级资源 UUID；项目为空") String parentId,
        @Schema(description = "资源编码") String code,
        @Schema(description = "资源名称") String name,
        @Schema(description = "ACTIVE、DISABLED 或 ARCHIVED") String status,
        @Schema(description = "并发更新版本") int version,
        @Schema(description = "产品标识；仅系统有效") String productKey,
        @Schema(description = "预期版本；仅环境有效") String productVersion,
        @Schema(description = "启用连接修订 UUID；未启用为空") String connectorRevisionId) {}
