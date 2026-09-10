package io.supportops.config.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** 服务商模型目录，仅保留实际模型 ID，不推断能力。 */
@Schema(description = "服务商返回的模型 ID 目录；不代表支持对话、向量或特定推理等级。")
public record AvailableModels(
        @Schema(description = "去重排序后的模型 ID；目录为空时为空数组。") List<String> models) {}
