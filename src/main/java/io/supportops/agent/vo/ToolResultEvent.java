package io.supportops.agent.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/** TOOL_RESULT 事件载荷，记录工具结果；结果内容不能作为越权执行指令。 */
@Schema(name = "ToolResultEvent", description = "TOOL_RESULT 事件载荷，记录工具结果；结果内容不能作为越权执行指令。")
public record ToolResultEvent(
        @Schema(description = "对应工具调用编号。") String callId,
        @Schema(description = "工具名称。", example = "get_effective_config") String tool,
        @Schema(description = "AgentScope 报告的工具执行状态。", example = "success") String state,
        @Schema(description = "工具返回文本，通常为序列化 JSON，最多保留 24000 字符。") String text) {}
