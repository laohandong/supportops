package io.supportops.integration.vo;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;

/** 标准协议固定外壳；data 由 capability 决定并在边界逐字段校验。 */
@Schema(description = "标准协议固定外壳；data 由 capability 决定并在边界逐字段校验。")
public record SourceEnvelope(
        @Schema(description = "协议版本，固定1.0") String protocolVersion,
        @Schema(description = "回显请求 UUID") String requestId,
        @Schema(description = "必须与服务器认可目标一致") Identity target,
        @Schema(description = "观测时间 ISO-8601，最近5分钟") String observedAt,
        @Schema(description = "现场来源身份") Source source,
        @Schema(description = "按固定能力严格校验的动态协议载荷") JsonNode data) {
    /** 协议目标身份，不允许响应修改系统和版本范围。 */
    @Schema(description = "协议目标身份，不允许响应修改系统和版本范围。")
    public record Identity(String systemCode, String environmentCode, String productKey, String productVersion) {}
    /** 来源身份只允许安全标识，不包含 URL 和凭据。 */
    @Schema(description = "来源身份只允许安全标识，不包含 URL 和凭据。")
    public record Source(String kind, String instanceId) {}
}
