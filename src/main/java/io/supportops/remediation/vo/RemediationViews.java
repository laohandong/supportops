package io.supportops.remediation.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

/** 固定修复的公开事实与持久化快照；动态配置仅限服务端采集的字符串键值。 */
public final class RemediationViews {
    /** 命名空间不能实例化。 */
    private RemediationViews() {}

    /** 现场快照不含凭据；revision 包含同名场景重置。 */
    @Schema(name = "RemediationSnapshot", description = "当前实例内置 OrderBridge 的脱敏事实")
    public record Snapshot(
            @Schema(description = "固定目标") String targetId,
            @Schema(description = "本次进程实例 UUID") String instanceId,
            @Schema(description = "单调环境修订") long revision,
            @Schema(description = "应用版本") String appVersion,
            @Schema(description = "提供配置，已脱敏") Map<String, String> provided,
            @Schema(description = "实际生效路径") String effectivePath,
            @Schema(description = "必要事实的 SHA256") String fingerprint,
            @Schema(description = "UTC 采集时间") String collectedAt,
            @Schema(description = "实际字段是否满足固定迁移条件") boolean applicable,
            @Schema(description = "最近环境变更来源") String changeSource) {}

    /** 目录只开放一个固定动作，验证最多一次。 */
    @Schema(name = "RemediationAction", description = "目录只开放一个固定动作，验证最多一次。")
    public record Action(
            @Schema(description = "固定动作 ID") String actionId,
            @Schema(description = "动作版本") int actionVersion,
            @Schema(description = "验证策略版本") int verificationVersion,
            @Schema(description = "名称") String name,
            @Schema(description = "验证 POST 最大次数") int maxVerificationCalls,
            @Schema(description = "总执行预算秒") int budgetSeconds) {}

    /** 预检查不写配置或发送业务请求。 */
    @Schema(name = "RemediationPreview", description = "预检查不写配置或发送业务请求。")
    public record Preview(
            @Schema(description = "固定动作", allOf = Action.class) Action action,
            @Schema(description = "服务端当前事实", allOf = Snapshot.class) Snapshot before,
            @Schema(description = "确定性迁移后的配置") Map<String, String> after,
            @Schema(description = "影响及无自动回滚边界") String impact) {}

    /** 创建后不可变；摘要覆盖所有字段与确定顺序的配置键。 */
    @Schema(name = "RemediationFrozenPlan", description = "创建后不可变；摘要覆盖所有字段与确定顺序的配置键。")
    public record FrozenPlan(
            @Schema(description = "提案结构版本，固定1") int schemaVersion,
            @Schema(description = "提案UUID") String planId,
            @Schema(description = "固定动作和验证策略版本", allOf = Action.class) Action action,
            @Schema(description = "冻结的服务端脱敏现场", allOf = Snapshot.class) Snapshot before,
            @Schema(description = "确定性变换后的完整脱敏配置") Map<String, String> after,
            @Schema(description = "名称") String name,
            @Schema(description = "人员理由，最多1000字符") String reason,
            @Schema(description = "服务端创建人UUID") String creatorId,
            @Schema(description = "可选已结束诊断UUID；空为独立提案") String sourceRunId,
            @Schema(description = "来源诊断持久化事件游标") List<Long> sourceEvidenceIds,
            @Schema(description = "可选被人工结束的原执行UUID") String supersedesExecutionId,
            @Schema(description = "UTC创建时间") String createdAt,
            @Schema(description = "提案截止Unix毫秒") long planExpiresAt,
            @Schema(description = "单管理员审批策略版本，固定1") int approvalPolicyVersion,
            @Schema(description = "影响及无自动回滚边界") String impact) {}

    /** 适配器应用回执；不能凭当前配置推断某次操作成功。 */
    @Schema(name = "RemediationApplicationReceipt", description = "适配器应用回执；不能凭当前配置推断某次操作成功。")
    public record ApplicationReceipt(
            @Schema(description = "原适配器操作UUID") String operationId,
            @Schema(description = "原进程实例UUID") String instanceId,
            @Schema(description = "NOT_STARTED尚未开始，NOT_APPLIED确认未应用，APPLIED确认已应用，UNKNOWN不明") String status,
            @Schema(description = "应用前环境修订") long beforeRevision,
            @Schema(description = "应用后环境修订，应用成功应增加1") long afterRevision,
            @Schema(description = "冻结的服务端脱敏现场") Map<String, String> before,
            @Schema(description = "确定性变换后的完整脱敏配置") Map<String, String> after,
            @Schema(description = "实际应用UTC时间；未应用为空") String appliedAt,
            @Schema(description = "受控错误码，成功为空") String errorCode) {}

    /** 独立业务 HTTP 验证及配置读回；不保存 Authorization 或原始响应。 */
    @Schema(name = "RemediationVerification", description = "独立业务 HTTP 验证及配置读回；不保存 Authorization 或原始响应。")
    public record Verification(
            @Schema(description = "固定请求UUID") String requestId,
            @Schema(description = "原适配器操作UUID") String operationId,
            @Schema(description = "原进程实例UUID") String instanceId,
            @Schema(description = "实际业务路径") String path,
            @Schema(description = "UTC开始时间") String startedAt,
            @Schema(description = "UTC完成时间；在途为空") String finishedAt,
            @Schema(description = "原HTTP响应状态；响应不明为空") Integer httpStatus,
            @Schema(description = "原下游是否接受；结构缺失或响应不明为空") Boolean accepted,
            @Schema(description = "下游回显原请求UUID；缺失为空") String echoedRequestId,
            @Schema(description = "下游回显实际路径；缺失为空") String echoedPath,
            @Schema(description = "本次验证之后的脱敏配置读回") Map<String, String> configReadback,
            @Schema(description = "验证读回环境修订") long revision,
            @Schema(description = "NOT_RUN未验证，RUNNING在验证，PASSED通过，FAILED失败，UNKNOWN不明") String status,
            @Schema(description = "受控错误码，成功为空") String errorCode) {}

    /** 同一 operationId 的应用与验证证据，inFlight 表示处理器尚未退出。 */
    @Schema(name = "RemediationReceipt", description = "同一 operationId 的应用与验证证据，inFlight 表示处理器尚未退出。")
    public record Receipt(
            @Schema(description = "原配置应用回执", allOf = ApplicationReceipt.class) ApplicationReceipt application,
            @Schema(description = "原业务验证证据；未采集为空", allOf = Verification.class) Verification verification,
            @Schema(description = "原处理器是否仍在途") boolean inFlight) {}

    /** 决策身份和截止时间均来自服务端。 */
    @Schema(name = "RemediationDecision", description = "决策身份和截止时间均来自服务端。")
    public record Decision(
            @Schema(description = "记录UUID；审计事件为递增游标") String id,
            @Schema(description = "固定动作或审计事件类型") String kind,
            @Schema(description = "服务端操作者UUID；启动恢复为空") String actorId,
            @Schema(description = "规范化冻结内容SHA256") String planHash,
            @Schema(description = "人员理由，最多1000字符") String reason,
            @Schema(description = "批准截止Unix毫秒，不晚于提案期限") long expiresAt,
            @Schema(description = "UTC创建时间") String createdAt) {}

    /** 综合状态与应用、验证事实分别展示。 */
    @Schema(name = "RemediationExecution", description = "综合状态与应用、验证事实分别展示。")
    public record Execution(
            @Schema(description = "记录UUID；审计事件为递增游标") String id,
            @Schema(description = "提案UUID") String planId,
            @Schema(description = "原适配器操作UUID") String operationId,
            @Schema(description = "原业务验证请求UUID") String verificationRequestId,
            @Schema(description = "原进程实例UUID") String instanceId,
            @Schema(description = "服务端操作者UUID；启动恢复为空") String actorId,
            @Schema(description = "RUNNING处理中，SUCCEEDED应用且通过，FAILED未应用，VERIFICATION_FAILED已应用验证失败，UNKNOWN待核查，CLOSED_UNRESOLVED人工结束不明") String status,
            @Schema(description = "NOT_STARTED尚未开始，NOT_APPLIED确认未应用，APPLIED确认已应用，UNKNOWN不明") String applicationStatus,
            @Schema(description = "NOT_RUN未验证，RUNNING在验证，PASSED通过，FAILED失败，UNKNOWN不明") String verificationStatus,
            @Schema(description = "原操作完整证据；缺失为空不能推断成功", allOf = Receipt.class) Receipt receipt,
            @Schema(description = "受控错误码，成功为空") String errorCode,
            @Schema(description = "UTC创建时间") String createdAt,
            @Schema(description = "UTC完成时间；在途为空") String finishedAt) {}

    /** 追加审计；payload 为受控类型序列化的脱敏 JSON 文本。 */
    @Schema(name = "RemediationEvent", description = "追加审计；payload 为受控类型序列化的脱敏 JSON 文本。")
    public record Event(
            @Schema(description = "记录UUID；审计事件为递增游标") long id,
            @Schema(description = "执行UUID；非执行事件为空") String executionId,
            @Schema(description = "服务端操作者UUID；启动恢复为空") String actorId,
            @Schema(description = "固定动作或审计事件类型") String kind,
            @Schema(description = "有界脱敏追加审计证据JSON文本") String payloadJson,
            @Schema(description = "UTC创建时间") String createdAt) {}

    /** 能力只是页面提示；命令重新检查权限、环境和版本。 */
    @Schema(name = "RemediationDetail", description = "能力只是页面提示；命令重新检查权限、环境和版本。")
    public record Detail(
            @Schema(description = "记录UUID；审计事件为递增游标") String id,
            @Schema(description = "PENDING_APPROVAL待批，APPROVED批准，EXECUTING执行，FINISHED结束，REJECTED驳回，CANCELLED撤销，EXPIRED过期，STALE环境失效") String status,
            @Schema(description = "提案乐观锁版本，从1开始") int version,
            @Schema(description = "规范化冻结内容SHA256") String planHash,
            @Schema(description = "不可变提案快照", allOf = FrozenPlan.class) FrozenPlan plan,
            @Schema(description = "唯一审批决定；未审批为空", allOf = Decision.class) Decision decision,
            @Schema(description = "唯一执行；未受理为空", allOf = Execution.class) Execution execution,
            @Schema(description = "当前可批准提示，命令仍重新校验") boolean canApprove,
            @Schema(description = "当前可执行提示，命令仍重新校验") boolean canExecute,
            @Schema(description = "当前可撤销提示") boolean canCancel,
            @Schema(description = "已确认处理器退出且可核查提示") boolean canReconcile,
            @Schema(description = "可人工结束未决提示") boolean canCloseUnknown,
            @Schema(description = "过期或环境变化阻断码，未阻断为空") String blockedReason) {}

    /** 稳定按时间与 UUID 倒序分页。 */
    @Schema(name = "RemediationPage", description = "稳定按时间与 UUID 倒序分页。")
    public record Page(
            @Schema(description = "本页提案详情") List<Detail> items,
            @Schema(description = "页码，从0开始") int page,
            @Schema(description = "页大小，默认20最多100") int size,
            @Schema(description = "匹配记录总数") long total) {}

    /** 当前操作者的原请求受理结果；拒绝结果亦保持稳定。 */
    @Schema(name = "RemediationRequestResult", description = "当前操作者的原请求受理结果；拒绝结果亦保持稳定。")
    public record RequestResult(
            @Schema(description = "固定请求UUID") String requestId,
            @Schema(description = "固定动作或审计事件类型") String kind,
            @Schema(description = "固定目标或命令资源") String targetId,
            @Schema(description = "原HTTP响应状态；响应不明为空") int httpStatus,
            @Schema(description = "受控错误码，成功为空") String errorCode,
            @Schema(description = "提案UUID") String planId,
            @Schema(description = "执行UUID；非执行事件为空") String executionId,
            @Schema(description = "当前持久化提案详情；拒绝可为空", allOf = Detail.class) Detail detail) {}

    /** 报告只读取已落库的证据和审计，不触发核查或业务请求。 */
    @Schema(name = "RemediationReport", description = "报告只读取已落库的证据和审计，不触发核查或业务请求。")
    public record Report(
            @Schema(description = "证据范围固定SYNTHETIC_LOOPBACK_HTTP") String evidenceClass,
            @Schema(description = "提案、审批和原执行证据", allOf = Detail.class) Detail remediation,
            @Schema(description = "按游标升序的持久化审计") List<Event> events) {}
}
