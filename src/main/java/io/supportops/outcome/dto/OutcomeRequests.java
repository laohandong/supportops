package io.supportops.outcome.dto;

import io.supportops.outcome.constant.OutcomeEvidenceType;
import io.supportops.outcome.constant.OutcomeStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 有界人工结果命令，不接受所有者、确认人或可执行参数。 */
public final class OutcomeRequests {
    public static final String UUID_PATTERN = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

    /** 请求命名空间不能实例化。 */
    private OutcomeRequests() {}

    /** 三类证据按类型校验；手工观察仅代表人员陈述。 */
    @Schema(name = "OutcomeEvidenceInput", description = "证据不是执行指令，不能包含采集地址或脚本")
    public record Evidence(
            @NotNull @Schema(description = "证据类别", requiredMode = Schema.RequiredMode.REQUIRED) OutcomeEvidenceType type,
            @Min(1) @Schema(description = "当前诊断持久化事件ID，仅RUN_EVENT必填") Long eventId,
            @Pattern(regexp = UUID_PATTERN) @Schema(description = "关联修复提案UUID，仅管理员可提交") String remediationId,
            @Size(max = 2000) @Schema(description = "人员确认摘要，最多2000字符") String summary,
            @Size(max = 2000) @Schema(description = "处理后的验证动作，手工观察必填") String verificationAction,
            @Size(max = 2000) @Schema(description = "处理后的观察结果，手工观察必填") String observation,
            @Size(max = 40) @Schema(description = "ISO 8601观察时间，手工观察必填") String observedAt) {}

    /** 版本0表示首次回填；以后必须说明变更原因。 */
    @Schema(name = "OutcomeSave", description = "固定请求恢复，成功只追加修订，不改变诊断终态和用量")
    public record Save(
            @NotBlank @Pattern(regexp = UUID_PATTERN) @Schema(description = "发送前固定的请求UUID", requiredMode = Schema.RequiredMode.REQUIRED) String requestId,
            @Min(0) @Schema(description = "所见结果版本，首次为0", requiredMode = Schema.RequiredMode.REQUIRED) int expectedVersion,
            @NotNull @Schema(description = "人员确认处理状态", requiredMode = Schema.RequiredMode.REQUIRED) OutcomeStatus status,
            @NotBlank @Size(max = 4000) @Schema(description = "处理措施或未处理原因，最多4000字符", requiredMode = Schema.RequiredMode.REQUIRED) String actionsTaken,
            @Size(max = 4000) @Schema(description = "验证说明或缺少验证信息，最多4000字符") String verificationSummary,
            @Size(max = 2000) @Schema(description = "剩余问题，最多2000字符") String remainingIssue,
            @Size(max = 1000) @Schema(description = "修订变更理由，已有记录必填") String changeReason,
            @NotNull @Size(max = 20) @Valid @Schema(description = "最多20条有界证据", requiredMode = Schema.RequiredMode.REQUIRED) List<Evidence> evidence) {}
}
