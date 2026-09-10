package io.supportops.memory.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 只有人员已确认的稳定事实才可写入项目记忆。 */
@Schema(name = "CreateProjectMemoryRequest", description = "只有人员已确认的稳定事实才可写入项目记忆。")
public record CreateProjectMemoryRequest(
        @Schema(
                        description = "约束内容，不能为空白，最多 1000 字符；保存时去除首尾空白。",
                        example = "生产变更需在维护窗口内执行。",
                        minLength = 1,
                        maxLength = 1000,
                        requiredMode = Schema.RequiredMode.REQUIRED)
                String content,
        @Schema(
                        description = "必须明确为 true；缺省 false 会被拒绝。",
                        example = "true",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                boolean confirmed) {}
