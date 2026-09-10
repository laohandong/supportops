package io.supportops.agent.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** ERROR 事件载荷，不返回异常消息和外部服务错误正文。 */
@Schema(name = "ExecutionErrorEvent", description = "ERROR 事件载荷，不返回异常消息和外部服务错误正文。")
public record ExecutionErrorEvent(
        @Schema(description = "异常类简单名称。", example = "IllegalStateException") String type,
        @Schema(description = "最多 4 个调用栈位置，用于本地排查。") List<String> location) {}
