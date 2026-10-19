package io.supportops.demo.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import io.swagger.v3.oas.annotations.media.Schema;

/** 自建下游接口响应；成功时返回 accepted，失败时返回 code，废弃路径错误还包含 path。 */
@Schema(
        name = "DownstreamResponse",
        description = "自建下游接口响应；成功时返回 accepted，失败时返回 code，废弃路径错误还包含 path。")
@JsonInclude(Include.NON_NULL)
public record DownstreamResponse(
        @Schema(description = "下游是否接收订单，仅成功时出现。", example = "true") Boolean accepted,
        @Schema(
                        description = "下游错误码，仅失败时出现。",
                        allowableValues = {
                            "TEMPORARILY_UNAVAILABLE",
                            "INVALID_CREDENTIAL",
                            "ROUTE_RETIRED"
                        })
                String code,
        @Schema(description = "被废弃的请求路径，仅 ROUTE_RETIRED 时出现。", example = "/downstream/v1/orders")
                String path,
        @Schema(description = "本次受控业务请求回显 UUID；独立手工请求为 manual") String requestId) {
    /** 兼容原有日志构造，未采集的回显编号为空。 */
    public DownstreamResponse(Boolean accepted, String code, String path) {
        this(accepted, code, path, null);
    }
}
