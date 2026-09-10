package io.supportops.agent.vo;

import org.apache.ibatis.annotations.AutomapConstructor;
import io.swagger.v3.oas.annotations.media.Schema;

/** 诊断任务快照。创建接口返回已受理任务，之后通过 status 判断执行结果。 */
@Schema(name = "DiagnosisRun", description = "诊断任务快照。创建接口返回已受理任务，之后通过 status 判断执行结果。")
public record DiagnosisRun(
        @Schema(description = "诊断任务唯一编号。", format = "uuid") String id,
        @Schema(description = "会话编号；同一会话复用最近三个已完成任务的问答。", format = "uuid") String sessionId,
        @Schema(description = "本次问题或补充信息。", example = "升级到 2.0 后订单同步失败，请检查原因。") String question,
        @Schema(
                        description =
                                "任务状态：QUEUED 等待；RUNNING 执行中；COMPLETED 完成；FAILED 失败；CANCELLED"
                                        + " 取消；TIMED_OUT 超时；INTERRUPTED 被应用重启或关闭中断；LIMIT_REACHED"
                                        + " 达到步数上限。",
                        allowableValues = {
                            "QUEUED",
                            "RUNNING",
                            "COMPLETED",
                            "FAILED",
                            "CANCELLED",
                            "TIMED_OUT",
                            "INTERRUPTED",
                            "LIMIT_REACHED"
                        })
                String status,
        @Schema(description = "诊断回答。执行中或未产生结果时为空；LIMIT_REACHED 时可能存在不完整回答。") String answer,
        @Schema(
                        description =
                                "任务失败或终止原因，正常时为空。包括"
                                    + " DIAGNOSIS_EXECUTION_FAILED、EMPTY_MODEL_RESPONSE、STEP_LIMIT_REACHED、TIME_BUDGET_EXCEEDED、CANCELLED_BY_USER、PROCESS_RESTARTED、SERVER_SHUTDOWN。")
                String errorCode,
        @Schema(description = "任务创建时间，ISO 8601。", format = "date-time") String createdAt,
        @Schema(description = "终态时间；执行中为 null。", format = "date-time", nullable = true)
                String finishedAt,
        @Schema(description = "终态记录的耗时，单位毫秒；执行中可能为 0，页面可由 createdAt 计算实时耗时。", example = "1200")
                long elapsedMs,
        @Schema(description = "模型累计报告的输入 Token 数；未报告时为 0，不代表实际未消耗。") long inputTokens,
        @Schema(description = "模型累计报告的输出 Token 数；未报告时为 0。") long outputTokens,
        @Schema(description = "提交用户 UUID；null 表示历史未归属或内部任务", nullable = true) String userId) {
    /** 主构造器用于 Mapper 按字段名映射包含归属的记录。 */
    @AutomapConstructor
    public DiagnosisRun {}

    /** 兼容内部任务与旧迁移调用，未提供用户时不虚构归属。 */
    public DiagnosisRun(String id, String sessionId, String question, String status, String answer,
            String errorCode, String createdAt, String finishedAt, long elapsedMs,
            long inputTokens, long outputTokens) {
        this(id, sessionId, question, status, answer, errorCode, createdAt, finishedAt,
                elapsedMs, inputTokens, outputTokens, null);
    }
}
