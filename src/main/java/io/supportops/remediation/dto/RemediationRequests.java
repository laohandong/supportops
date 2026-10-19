package io.supportops.remediation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 有界命令；没有地址、配置参数、审批身份或可执行脚本字段。 */
public final class RemediationRequests {
    /** DTO 命名空间不能实例化。 */
    private RemediationRequests() {}

    /** UUID 正文不会承担任何执行参数。 */
    public static final String UUID_PATTERN = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

    /** 固定动作的只读预检查。 */
    @Schema(name = "RemediationPreviewRequest", description = "固定动作的只读预检查。")
    public record PreviewRequest(
            @NotBlank @Schema(description = "固定动作 ID", allowableValues = "ORDERBRIDGE_MIGRATE_SYNC_PATH", requiredMode = Schema.RequiredMode.REQUIRED) String actionId) {}

    /** 不接受网页快照；复制提案亦重新采集现场。 */
    @Schema(name = "RemediationCreate", description = "不接受网页快照；复制提案亦重新采集现场。")
    public record Create(
            @NotBlank @Pattern(regexp = UUID_PATTERN) @Schema(description = "固定请求 UUID", requiredMode = Schema.RequiredMode.REQUIRED) String requestId,
            @NotBlank @Schema(description = "固定动作 ID", requiredMode = Schema.RequiredMode.REQUIRED) String actionId,
            @NotBlank @Size(max = 120) @Schema(description = "提案名称，最多120字符", requiredMode = Schema.RequiredMode.REQUIRED) String name,
            @NotBlank @Size(max = 1000) @Schema(description = "人工理由，最多1000字符", requiredMode = Schema.RequiredMode.REQUIRED) String reason,
            @Pattern(regexp = UUID_PATTERN) @Schema(description = "可选已结束诊断 UUID", nullable = true) String sourceRunId,
            @Pattern(regexp = UUID_PATTERN) @Schema(description = "可选已人工结束的未决执行 UUID", nullable = true) String supersedesExecutionId) {}

    /** 审批、撤销和执行绑定同一不可变摘要与版本。 */
    @Schema(name = "RemediationCommand", description = "审批、撤销和执行绑定同一不可变摘要与版本。")
    public record Command(
            @NotBlank @Pattern(regexp = UUID_PATTERN) @Schema(description = "固定请求 UUID", requiredMode = Schema.RequiredMode.REQUIRED) String requestId,
            @Min(1) @Schema(description = "页面读取的提案版本，从1开始", requiredMode = Schema.RequiredMode.REQUIRED) int expectedVersion,
            @NotBlank @Pattern(regexp = "[a-f0-9]{64}") @Schema(description = "不可变提案 SHA256", requiredMode = Schema.RequiredMode.REQUIRED) String planHash,
            @Size(max = 1000) @Schema(description = "驳回或人工结束必须提供理由，最多1000字符", nullable = true) String reason) {}

    /** 旧入口必须完整引用批准提案；复用同一命令协议。 */
    @Schema(name = "RemediationLegacyExecute", description = "旧入口必须完整引用批准提案；复用同一命令协议。")
    public record LegacyExecute(
            @NotBlank @Pattern(regexp = UUID_PATTERN) @Schema(description = "已批准提案 UUID", requiredMode = Schema.RequiredMode.REQUIRED) String planId,
            @NotBlank @Pattern(regexp = UUID_PATTERN) @Schema(description = "固定请求 UUID", requiredMode = Schema.RequiredMode.REQUIRED) String requestId,
            @Min(1) @Schema(description = "提案版本", requiredMode = Schema.RequiredMode.REQUIRED) int expectedVersion,
            @NotBlank @Pattern(regexp = "[a-f0-9]{64}") @Schema(description = "不可变提案摘要", requiredMode = Schema.RequiredMode.REQUIRED) String planHash) {}
}
