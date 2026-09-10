package io.supportops.config.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/** 实际模型请求使用的兼容参数；未设置的字符串选项为空。 */
@Schema(name = "GenerationOptions", description = "实际模型请求使用的兼容参数；未设置的字符串选项为空。")
public record GenerationOptions(
        @Schema(
                        description = "输出上限对应的协议字段。",
                        allowableValues = {"max_tokens", "max_completion_tokens"},
                        example = "max_tokens")
                String maxTokensField,
        @Schema(description = "单次模型调用的输出上限，合法配置范围 1–32768；路由无法解析时可为 0。", example = "2500")
                int maxOutputTokens,
        @Schema(description = "温度字符串，范围 0–2；空字符串或 omit 表示不发送。", example = "0.1") String temperature,
        @Schema(
                        description = "思考模式；空字符串表示不发送。",
                        allowableValues = {"", "enabled", "disabled"})
                String thinking,
        @Schema(description = "推理强度，取值由服务商约定；未设置时为空。", example = "") String reasoningEffort) {}
