package io.supportops.agent.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/** USAGE 事件载荷，单次模型调用报告的用量。 */
@Schema(name = "UsageEvent", description = "USAGE 事件载荷，单次模型调用报告的用量。")
public record UsageEvent(
        @Schema(description = "本次输入 Token 数。", example = "120") long inputTokens,
        @Schema(description = "本次输出 Token 数。", example = "35") long outputTokens) {}
