package io.supportops.integration.vo;

import com.fasterxml.jackson.databind.JsonNode;
import io.supportops.project.vo.TargetView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 机器接口的显式投影，不复用人员后台或原始模型事件。 */
public final class OpenViews {
    /** 响应类型容器不能实例化。 */
    private OpenViews() {}
    /** 可恢复的异步受理回执。 */
    @Schema(description = "可恢复的异步受理回执。")
    public record Receipt(@Schema(description = "固定请求 UUID") String requestId,
            @Schema(description = "诊断 UUID") String runId,
            @Schema(description = "当前任务状态") String status,
            @Schema(description = "受理时间") String acceptedAt,
            @Schema(description = "安全目标摘要") TargetView target,
            @Schema(description = "是否重放同一受理") boolean replayed,
            @Schema(description = "任务读取路径") String diagnosisPath,
            @Schema(description = "事件轮询路径") String eventsPath,
            @Schema(description = "终态读取路径") String resultPath) {}
    /** Token 是既有逐轮报告累计，未报告时不能解释为零费用。 */
    @Schema(description = "Token 是既有逐轮报告累计，未报告时不能解释为零费用。")
    public record Usage(@Schema(description = "已报告输入 Token") long inputTokens,
            @Schema(description = "已报告输出 Token") long outputTokens,
            @Schema(description = "是否至少收到一次用量报告；不保证每轮均完整") boolean reported) {}
    /** 独立应用任务快照。 */
    @Schema(description = "独立应用任务快照。")
    public record Diagnosis(@Schema(description = "诊断 UUID") String runId,
            @Schema(description = "固定请求 UUID") String requestId,
            @Schema(description = "QUEUED、RUNNING 或既有终态枚举") String status,
            @Schema(description = "安全目标摘要") TargetView target,
            @Schema(description = "持久化模型回答 Markdown") String answerMarkdown,
            @Schema(description = "受控错误代码，正常为空") String errorCode,
            @Schema(description = "已报告用量") Usage usage,
            @Schema(description = "创建时间") String createdAt,
            @Schema(description = "终态时间，执行中为空") String finishedAt,
            @Schema(description = "耗时毫秒") long elapsedMs) {}
    /** 单个白名单事件，超过16KiB只返回摘要及证据链接。 */
    @Schema(description = "单个白名单事件，超过16KiB只返回摘要及证据链接。")
    public record Event(@Schema(description = "持久化单调递增游标") long id,
            @Schema(description = "公开事件种类") String kind,
            @Schema(description = "按种类决定的安全协议事实") JsonNode content,
            @Schema(description = "是否截断正文") boolean truncated,
            @Schema(description = "可为空的持久化安全证据路径") String evidenceRef,
            @Schema(description = "事实保存时间") String createdAt) {}
    /** 有界事件页，以 nextAfter 继续，不因内部事件被省略而无限读取同一页。 */
    @Schema(description = "有界事件页，以 nextAfter 继续，不因内部事件被省略而无限读取同一页。")
    public record EventPage(@Schema(description = "最多200项且整页小于1MiB") List<Event> events,
            @Schema(description = "下一页 after 游标") long nextAfter,
            @Schema(description = "本页是否达到数量限制或大小限制，可能还有记录") boolean hasMore) {}
    /** 安全证据只读持久化快照，不再次访问现场。 */
    @Schema(description = "安全证据只读持久化快照，不再次访问现场。")
    public record Evidence(@Schema(description = "证据事件编号") long eventId,
            @Schema(description = "证据种类") String kind,
            @Schema(description = "最多2MiB的安全事实") JsonNode content,
            @Schema(description = "保存时间") String createdAt) {}
    /** 终态结果与引用分开描述，不自动声称故障解决。 */
    @Schema(description = "终态结果与引用分开描述，不自动声称故障解决。")
    public record Result(@Schema(description = "终态快照；失败终态也返回200") Diagnosis diagnosis,
            @Schema(description = "持久化事实读取路径") List<String> evidenceRefs) {}
}
