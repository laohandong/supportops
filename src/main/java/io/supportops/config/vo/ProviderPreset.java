package io.supportops.config.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/** 服务商预设；非法基础地址被省略为字符串空值，所有密钥均不返回。 */
@Schema(name = "ProviderPreset", description = "服务商预设；非法基础地址被省略为字符串空值，所有密钥均不返回。")
public record ProviderPreset(
        @Schema(description = "预设基础地址；自定义接口可为空。", example = "https://api.openai.com/v1")
                String baseUrl,
        @Schema(description = "预设对话模型名称。", example = "gpt-4.1-mini") String model,
        @Schema(description = "预设向量模型名称，不提供时为空。", example = "text-embedding-3-small")
                String embeddingModel,
        @Schema(description = "该服务商是否允许用于向量功能；DeepSeek 预设为 false。") boolean embeddings) {}
