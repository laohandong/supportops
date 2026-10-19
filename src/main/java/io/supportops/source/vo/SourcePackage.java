package io.supportops.source.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 来源包 v1；仅包含管理员确认的脱敏文本和外部身份，不能携带执行配置。 */
@Schema(description = "不可变脱敏来源包 v1，摘要仅验证完整性，不证明远端真实性")
public record SourcePackage(
        @Min(1) @Max(1) @Schema(description = "固定协议版本1") int schemaVersion,
        @NotBlank
                @Pattern(
                        regexp =
                                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
                @Schema(description = "不可变导出包UUID")
                String packageId,
        @NotBlank
                @Pattern(
                        regexp =
                                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
                @Schema(description = "来源安装UUID")
                String sourceSystemId,
        @NotBlank
                @Pattern(
                        regexp =
                                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
                @Schema(description = "外部诊断UUID")
                String sourceRunId,
        @NotBlank
                @Pattern(
                        regexp =
                                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
                @Schema(description = "外部结果修订UUID")
                String outcomeRevisionId,
        @NotBlank @Size(max = 40) @Schema(description = "服务端UTC导出时间") String exportedAt,
        @NotNull @Valid @Schema(description = "受限脱敏内容") Content content,
        @NotBlank @Size(max = 40) @Schema(description = "本次脱敏确认UTC时间") String redactionConfirmedAt,
        @NotBlank @Pattern(regexp = "[a-f0-9]{64}") @Schema(description = "除本字段外规范JSON的SHA256")
                String contentSha256) {
    /** 来源文字不能包含原始证据附件、凭据或执行参数。 */
    @Schema(description = "管理员编辑并确认的脱敏来源文字")
    public record Content(
            @NotBlank @Size(max = 6000) @Schema(description = "脱敏问题，1至6000字符")
                    String redactedQuestion,
            @NotNull @Size(max = 1000) @Schema(description = "脱敏摘要，最多1000字符")
                    String redactedSummary,
            @NotBlank
                    @Pattern(regexp = "RESOLVED|PARTIALLY_RESOLVED|UNRESOLVED|UNCONFIRMED")
                    @Schema(description = "来源处理结果状态快照")
                    String outcomeStatus) {}
}
