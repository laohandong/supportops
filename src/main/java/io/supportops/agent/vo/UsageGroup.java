package io.supportops.agent.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/** 分组用量与任务结果；完成状态不代表回答正确。 */
@Schema(description = "已报告对话用量统计")
public record UsageGroup(
        @Schema(description = "总览为 all，趋势为 UTC 时间，排行为会话 ID") String groupId,
        @Schema(description = "会话内的问题示例，非排行为空") String question,
        @Schema(description = "诊断轮数，包含所有状态") long runs,
        @Schema(description = "去重会话数") long sessions,
        @Schema(description = "COMPLETED 轮数") long completed,
        @Schema(description = "没有 USAGE 事件的轮数，不代表零消耗") long unreported,
        @Schema(description = "已报告输入 Token") long inputTokens,
        @Schema(description = "已报告输出 Token") long outputTokens,
        @Schema(description = "已结算耗时，毫秒") long elapsedMs) {}
