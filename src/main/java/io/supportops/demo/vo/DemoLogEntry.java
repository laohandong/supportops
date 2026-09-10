package io.supportops.demo.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import io.swagger.v3.oas.annotations.media.Schema;

/** 最近一次真实 HTTP 同步日志。传输失败时没有 httpStatus 和 response；尚无日志时为 {}。 */
@Schema(
        name = "DemoLogEntry",
        description = "最近一次真实 HTTP 同步日志。传输失败时没有 httpStatus 和 response；尚无日志时为 {}。")
@JsonInclude(Include.NON_NULL)
public record DemoLogEntry(
        @Schema(description = "请求跟踪编号。", format = "uuid") String requestId,
        @Schema(description = "请求发生时间。", format = "date-time") String timestamp,
        @Schema(description = "业务操作名称。", example = "synchronize_order") String operation,
        @Schema(description = "实际请求的业务路径，传输失败时可能省略。", example = "/v1/orders") String targetPath,
        @Schema(description = "下游返回的 HTTP 状态码，与工作台接口本身的状态码不同。", example = "410") Integer httpStatus,
        @Schema(description = "下游响应正文。", schemaResolution = Schema.SchemaResolution.ALL_OF)
                DownstreamResponse response,
        @Schema(description = "传输错误码，仅请求未正常返回时出现。", example = "TRANSPORT_FAILURE") String error) {}
