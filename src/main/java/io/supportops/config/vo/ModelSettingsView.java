package io.supportops.config.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

/** 模型配置页面所需的预设、当前路由及版本标记。响应标记 Cache-Control: no-store，不返回密钥。 */
@Schema(
        name = "ModelSettingsView",
        description = "模型配置页面所需的预设、当前路由及版本标记。响应标记 Cache-Control: no-store，不返回密钥。")
public record ModelSettingsView(
        @Schema(
                        description = "当前配置版本标记。保存或恢复时必须传回最新值；每次修改和应用重启后会更新。",
                        example = "从读取接口复制当前 revision")
                String revision,
        @Schema(description = "按服务商标识组织的预设字典；键包括 bailian、deepseek、openai、custom 和自定义标识。")
                Map<String, ProviderPreset> providers,
        @Schema(description = "对话角色的当前配置。", schemaResolution = Schema.SchemaResolution.ALL_OF)
                RoleSettings model,
        @Schema(description = "向量角色的当前配置。", schemaResolution = Schema.SchemaResolution.ALL_OF)
                RoleSettings embedding) {}
