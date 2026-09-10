package io.supportops.memory.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/** 人员显式确认并保存在本机的项目约束。 */
@Schema(name = "ProjectMemory", description = "人员显式确认并保存在本机的项目约束。")
public record ProjectMemory(
        @Schema(description = "项目约束唯一编号。", format = "uuid") String id,
        @Schema(description = "已确认的稳定事实或执行约束。", example = "生产变更需在维护窗口内由实施负责人执行。") String content,
        @Schema(description = "确认并保存的时间，ISO 8601。", format = "date-time") String createdAt) {}
