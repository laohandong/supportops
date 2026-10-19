package io.supportops.memory.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/** 人员显式确认并保存在本机的项目约束。 */
@Schema(name = "ProjectMemory", description = "人员显式确认并保存在本机的项目约束。")
public record ProjectMemory(
        @Schema(description = "项目约束唯一编号。", format = "uuid") String id,
        @Schema(description = "已确认的稳定事实或执行约束。", example = "生产变更需在维护窗口内由实施负责人执行。") String content,
        @Schema(description = "确认并保存的时间，ISO 8601。", format = "date-time") String createdAt,
        @Schema(description = "所属项目 UUID") String projectId,
        @Schema(description = "所属系统；为空表示项目级约束") String systemId,
        @Schema(description = "所属环境；为空表示项目或系统级约束") String environmentId) {
    /** 原示例上下文构造器保留字段兼容。 */
    public ProjectMemory(String id, String content, String createdAt) {
        this(id, content, createdAt, io.supportops.integration.service.support.EnterpriseIds.PROJECT, null, null);
    }
}
