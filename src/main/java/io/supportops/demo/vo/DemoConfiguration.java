package io.supportops.demo.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

/** 示例应用提供的配置与实际生效配置；不返回业务凭据。 */
@Schema(name = "DemoConfiguration", description = "示例应用提供的配置与实际生效配置；不返回业务凭据。")
public record DemoConfiguration(
        @Schema(
                        description = "原始配置字典；升级故障中可能保留已废弃的 orders.path。",
                        example = "{\"orders.path\":\"/v2/orders\"}")
                Map<String, String> provided,
        @Schema(description = "实际生效的配置字典。", example = "{\"sync.targetPath\":\"/v1/orders\"}")
                Map<String, String> effective,
        @Schema(description = "是否已提供业务凭据；不表示该凭据能通过下游鉴权。") boolean credentialConfigured,
        @Schema(description = "采集时间，ISO 8601。", format = "date-time") String observedAt) {}
