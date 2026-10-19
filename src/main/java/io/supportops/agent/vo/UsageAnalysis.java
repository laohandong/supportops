package io.supportops.agent.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** 同一快照中的总览、补零趋势及排行。 */
@Schema(description = "按诊断创建时间归属的 UTC 用量，不含向量或费用")
public record UsageAnalysis(
        @Schema(description = "开始日期，含当天") String from,
        @Schema(description = "结束日期，含当天") String to,
        @Schema(description = "hour 小时，day 天") String granularity,
        @Schema(description = "范围内总览", allOf = UsageGroup.class) UsageGroup summary,
        @Schema(description = "按时间升序，空时间段补零") List<UsageGroup> timeline,
        @Schema(description = "范围内会话累计 Token 降序 Top 20，同量按会话 ID") List<UsageGroup> topSessions,
        @Schema(description = "独立应用维度用量，包含停用应用；总量已包含这些任务，不重复累计") List<UsageGroup> applications) {
    /** 原统计调用保留相同字段与默认空应用维度。 */
    public UsageAnalysis(String from, String to, String granularity, UsageGroup summary,
            List<UsageGroup> timeline, List<UsageGroup> topSessions) {
        this(from, to, granularity, summary, timeline, topSessions, List.of());
    }
}
