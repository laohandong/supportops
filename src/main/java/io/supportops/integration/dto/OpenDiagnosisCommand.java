package io.supportops.integration.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 开放 API 受理载荷，所有未知字段由专用严格解码器拒绝。 */
@Schema(description = "开放 API 受理载荷，所有未知字段由专用严格解码器拒绝。")
public record OpenDiagnosisCommand(@Schema(description = "固定请求 UUID，与 runId 不同") String requestId,
        @Schema(description = "明确授权环境 UUID") String environmentId,
        @Schema(description = "问题1至6000个 UTF-16字符，禁止孤立代理字符") String question,
        @Schema(description = "是否仅关键词检索，默认false") boolean lexicalOnly,
        @Schema(description = "可选外部工单或事件引用，仅作事实关联") ExternalReference externalReference) {
    /** 固定外部引用，不接受 URL 或可执行指令字段。 */
    @Schema(description = "固定外部引用，不接受 URL 或可执行指令字段。")
    public record ExternalReference(@Schema(description = "TICKET、INCIDENT 或 OTHER") String type,
            @Schema(description = "来源标识，最多128字符") String id) {}
}
