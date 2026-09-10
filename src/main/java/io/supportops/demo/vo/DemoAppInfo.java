package io.supportops.demo.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/** 固定 OrderBridge 示例应用的版本与环境信息。 */
@Schema(name = "DemoAppInfo", description = "固定 OrderBridge 示例应用的版本与环境信息。")
public record DemoAppInfo(
        @Schema(description = "应用名称。", example = "OrderBridge") String application,
        @Schema(description = "当前部署版本。", example = "2.0") String version,
        @Schema(description = "示例环境标识。", example = "local-demo") String environment,
        @Schema(description = "事实采集时间，ISO 8601。", format = "date-time") String observedAt) {}
