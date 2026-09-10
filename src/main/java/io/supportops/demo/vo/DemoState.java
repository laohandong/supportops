package io.supportops.demo.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/** 人工操作页面使用的示例环境快照。 */
@Schema(name = "DemoState", description = "人工操作页面使用的示例环境快照。")
public record DemoState(
        @Schema(description = "当前场景标识，取值与 ScenarioRequest.scenario 一致。", example = "migration")
                String scenario,
        @Schema(description = "应用信息。", schemaResolution = Schema.SchemaResolution.ALL_OF)
                DemoAppInfo app,
        @Schema(description = "提供与生效的配置。", schemaResolution = Schema.SchemaResolution.ALL_OF)
                DemoConfiguration configuration,
        @Schema(description = "最近一次请求日志。", schemaResolution = Schema.SchemaResolution.ALL_OF)
                DemoLogEntry lastRequest,
        @Schema(description = "始终为 true，表示自建合成环境。", example = "true")
                boolean syntheticEnvironment) {}
