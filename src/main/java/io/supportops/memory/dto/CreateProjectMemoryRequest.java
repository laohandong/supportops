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
                boolean confirmed,
        @Schema(description = "项目 UUID；省略只兼容历史示例") String projectId,
        @Schema(description = "可选系统 UUID，必须属于项目") String systemId,
        @Schema(description = "可选环境 UUID，必须属于系统") String environmentId) {
    /** 兼容原有项目约束输入。 */
    public CreateProjectMemoryRequest(String content, boolean confirmed) { this(content, confirmed, null, null, null); }
}
