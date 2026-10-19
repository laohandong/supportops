package io.supportops.outcome.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 人工预览后的来源导出命令；身份与时间由服务端捕获。 */
@Schema(description = "固定请求绑定所见结果修订和人工脱敏文本")
public record EvaluationExportRequest(
        @NotBlank
                @Pattern(
                        regexp =
                                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
                @Schema(description = "固定请求UUID")
                String requestId,
        @NotBlank
                @Pattern(
                        regexp =
                                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
                @Schema(description = "所见当前结果修订UUID")
                String expectedOutcomeRevisionId,
        @NotBlank @Size(max = 6000) @Schema(description = "待导出的脱敏问题") String redactedQuestion,
        @NotNull @Size(max = 1000) @Schema(description = "待导出的脱敏摘要") String redactedSummary,
        @Schema(description = "人工确认，必须true") boolean redactionConfirmed) {}
