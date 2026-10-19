package io.supportops.outcome.vo;

import io.supportops.outcome.constant.OutcomeEvidenceType;
import io.supportops.outcome.constant.OutcomeStatus;
import io.supportops.remediation.vo.RemediationViews.Receipt;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 明确响应模型；普通用户和管理员由服务端选择不同证据投影。 */
public final class OutcomeViews {
    /** 模型命名空间不能实例化。 */
    private OutcomeViews() {}

    /** 系统采集快照仅用于持久化，不能直接向普通用户返回。 */
    public record EvidenceSnapshot(OutcomeEvidenceType type, Long eventId, String remediationId,
            String summary, String verificationAction, String observation, String observedAt,
            String sourceHash, String executionStatus, String applicationStatus, String verificationStatus, Receipt receipt) {}

    /** 处理事实不参与模型质量计分。 */
    public record Content(OutcomeStatus status, String actionsTaken, String verificationSummary,
            String remainingIssue, String changeReason, List<EvidenceSnapshot> evidence) {}

    /** 角色证据响应契约，具体实现由服务端决定。 */
    @Schema(description = "由服务端角色选择的证据白名单", anyOf = {UserEvidence.class, AdminEvidence.class})
    public sealed interface EvidenceView permits UserEvidence, AdminEvidence {}

    /** 普通用户白名单，不含提案、操作ID及完整回执。 */
    @Schema(name = "OutcomeUserEvidence", description = "本人来源及人员陈述，修复只展示三个独立状态和人工摘要")
    public record UserEvidence(
            @Schema(description = "证据类别") OutcomeEvidenceType type,
            @Schema(description = "本人诊断事件ID，修复为空") Long eventId,
            @Schema(description = "人工摘要") String summary,
            @Schema(description = "人员验证动作") String verificationAction,
            @Schema(description = "人员观察结果") String observation,
            @Schema(description = "人员观察UTC时间") String observedAt,
            @Schema(description = "修复执行状态") String executionStatus,
            @Schema(description = "应用状态") String applicationStatus,
            @Schema(description = "验证状态") String verificationStatus) implements EvidenceView {}

    /** 管理员持久化回执投影，不触发修复或核查。 */
    @Schema(name = "OutcomeAdminEvidence", description = "管理员可读的关联修复身份、证据摘要及原回执")
    public record AdminEvidence(
            @Schema(description = "白名单摘要", schemaResolution = Schema.SchemaResolution.ALL_OF) UserEvidence summary,
            @Schema(description = "关联修复UUID") String remediationId,
            @Schema(description = "原证据SHA256") String sourceHash,
            @Schema(description = "管理员应用及验证回执", schemaResolution = Schema.SchemaResolution.ALL_OF) Receipt receipt) implements EvidenceView {}

    /** 不可变确认修订，actor和时间由服务端确定。 */
    @Schema(name = "OutcomeRevision", description = "人员确认的不可变处理结果修订")
    public record Revision(
            @Schema(description = "修订UUID") String id,
            @Schema(description = "修订号，从1开始") int version,
            @Schema(description = "人员处理状态") OutcomeStatus status,
            @Schema(description = "处理措施，最多4000字符") String actionsTaken,
            @Schema(description = "验证说明，最多4000字符") String verificationSummary,
            @Schema(description = "剩余问题，最多2000字符") String remainingIssue,
            @Schema(description = "变更理由，最多1000字符") String changeReason,
            @Schema(description = "按角色选择的证据，最多20条") List<EvidenceView> evidence,
            @Schema(description = "确认人UUID") String actorId,
            @Schema(description = "UTC确认时间") String confirmedAt,
            @Schema(description = "原内容摘要") String contentSha256) {}

    /** 未回填与未解决严格区分。 */
    @Schema(name = "OutcomeDetail", description = "处理结果当前版本，未回填时版本为零且修订为空")
    public record Detail(
            @Schema(description = "诊断UUID") String runId,
            @Schema(description = "结果UUID，未回填为空") String id,
            @Schema(description = "当前版本，未回填为0") int version,
            @Schema(description = "人员确认修订，未回填为空", schemaResolution = Schema.SchemaResolution.ALL_OF) Revision outcome,
            @Schema(description = "终态可回填提示") boolean canSave,
            @Schema(description = "管理员案例入口提示") boolean canCreateCase) {}

    /** 历史数据库分页，按修订号倒序。 */
    @Schema(name = "OutcomeHistory", description = "人员处理结果修订的数据库分页")
    public record History(@Schema(description = "不可变历史修订") List<Revision> items,
            @Schema(description = "总数") long total, @Schema(description = "从0开始页") int page,
            @Schema(description = "1至100，默认20") int size) {}

    /** 回执固定指向原修订，后续编辑不会改变。 */
    @Schema(name = "OutcomeRequestResult", description = "固定请求终局回执，保留原修订和原拒绝结果")
    public record RequestResult(@Schema(description = "原请求UUID") String requestId,
            @Schema(description = "原HTTP状态") int httpStatus,
            @Schema(description = "错误码，成功为空") String errorCode,
            @Schema(description = "来源诊断UUID") String runId,
            @Schema(description = "结果UUID") String resourceId,
            @Schema(description = "原确认修订", schemaResolution = Schema.SchemaResolution.ALL_OF) Revision revision) {}

    /** 案例服务最小来源投影，显式身份校验，仅管理员。 */
    public record Source(String runId, String question, String revisionId, int version,
            String outcomeHash, String confirmedAt) {}
}
