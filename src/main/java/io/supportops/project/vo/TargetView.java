package io.supportops.project.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/** 服务器解析的完整诊断目标；不接受模型修改身份字段。 */
@Schema(description = "服务器解析的完整诊断目标；不接受模型修改身份字段。")
public record TargetView(
        @Schema(description = "项目 UUID") String projectId,
        @Schema(description = "项目名称") String projectName,
        @Schema(description = "系统 UUID") String systemId,
        @Schema(description = "系统编码") String systemCode,
        @Schema(description = "系统名称") String systemName,
        @Schema(description = "环境 UUID") String environmentId,
        @Schema(description = "环境编码") String environmentCode,
        @Schema(description = "环境名称") String environmentName,
        @Schema(description = "产品标识") String productKey,
        @Schema(description = "预期产品版本") String productVersion,
        @Schema(description = "启用连接修订 UUID") String connectorRevisionId) {}
