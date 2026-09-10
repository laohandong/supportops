package io.supportops.demo.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import io.swagger.v3.oas.annotations.media.Schema;

/** 一次业务同步的结果。接口返回 HTTP 200 只表示已完成操作，应检查 success 和 httpStatus 判断业务结果。 */
@Schema(
        name = "SyncResult",
        description = "一次业务同步的结果。接口返回 HTTP 200 只表示已完成操作，应检查 success 和 httpStatus 判断业务结果。")
@JsonInclude(Include.NON_NULL)
public record SyncResult(
        @Schema(description = "本次业务请求编号。", format = "uuid") String requestId,
        @Schema(description = "下游 HTTP 状态码，可能为 200、401、410 或 503；传输失败时省略。", example = "410")
                Integer httpStatus,
        @Schema(description = "只有下游返回 HTTP 200 时为 true。", example = "false") boolean success,
        @Schema(
                        description = "传输失败时返回 TRANSPORT_FAILURE，正常取得下游响应时省略。",
                        example = "TRANSPORT_FAILURE")
                String error) {}
