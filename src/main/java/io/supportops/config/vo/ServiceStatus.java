package io.supportops.config.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/** 工作台服务状态，只检查本地配置，不探测外部模型服务。 */
@Schema(name = "ServiceStatus", description = "工作台服务状态，只检查本地配置，不探测外部模型服务。")
public record ServiceStatus(
        @Schema(description = "对话模型是否已完整配置。") boolean modelConfigured,
        @Schema(description = "对话模型名称。", example = "qwen-plus") String model,
        @Schema(description = "向量服务是否已完整配置；false 不影响文档上传、关键词检索和仅关键词诊断。")
                boolean embeddingConfigured,
        @Schema(description = "向量模型名称，禁用时可为空。", example = "text-embedding-v3")
                String embeddingModel,
        @Schema(description = "当前部署环境标识。", example = "local-demo") String environment,
        @Schema(description = "对话服务路由。", schemaResolution = Schema.SchemaResolution.ALL_OF)
                ModelRoute modelRoute,
        @Schema(description = "向量服务路由。", schemaResolution = Schema.SchemaResolution.ALL_OF)
                ModelRoute embeddingRoute,
        @Schema(description = "对话生成参数。", schemaResolution = Schema.SchemaResolution.ALL_OF)
                GenerationOptions generation) {}
