package io.supportops.api.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/** 业务接口统一错误响应。error 是稳定错误码；不返回凭据、服务商原始错误正文或异常消息。 */
@Schema(name = "ApiError", description = "业务接口统一错误响应。error 是稳定错误码；不返回凭据、服务商原始错误正文或异常消息。")
public record ErrorResponse(
        @Schema(
                        description = "错误码，具体含义见当前接口对应的 HTTP 错误响应说明。",
                        example = "INVALID_INPUT",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                String error) {}
