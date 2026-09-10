package io.supportops.agent.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/** TOOL_CALL 事件载荷，记录模型调用工具的参数。 */
@Schema(name = "ToolCallEvent", description = "TOOL_CALL 事件载荷，记录模型调用工具的参数。")
public record ToolCallEvent(
        @Schema(description = "工具调用编号。") String callId,
        @Schema(description = "已注册的工具名称。", example = "get_effective_config") String tool,
        @Schema(description = "参数文本，通常为 JSON；最多保留 24000 字符。", example = "{}") String arguments) {}
