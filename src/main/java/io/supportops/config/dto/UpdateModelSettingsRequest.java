package io.supportops.config.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 保存一个角色的完整配置。保存不调用服务商；未填写密钥也可保存草稿。 */
@Schema(name = "UpdateModelSettingsRequest", description = "保存一个角色的完整配置。保存不调用服务商；未填写密钥也可保存草稿。")
public record UpdateModelSettingsRequest(
        @Schema(
                        description = "服务商标识，取自读取接口 providers 字典；仅 embedding 角色还可使用 disabled。",
                        example = "deepseek",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                String provider,
        @Schema(
                        description = "模型名称，省略或留空时使用预设；自定义接口必须提供。",
                        maxLength = 200,
                        example = "deepseek-v4-flash")
                String model,
        @Schema(
                        description = "HTTPS 基础地址，本机可用 HTTP；不含凭据、查询参数或完整接口路径。省略时使用预设。",
                        maxLength = 1000,
                        example = "https://api.deepseek.com")
                String baseUrl,
        @Schema(
                        description =
                                "仅写入的模型密钥；replace 时必须填写，最长 4096 字符且不能含内部空白或非 ASCII"
                                        + " 字符。不会出现在响应或文档示例中。",
                        accessMode = Schema.AccessMode.WRITE_ONLY,
                        maxLength = 4096)
                String apiKey,
        @Schema(
                        description =
                                "keep：仅同服务商同基础地址保留当前密钥，变更后清空；replace：使用新密钥；remove：清空且不回退环境变量。disabled"
                                    + " 角色不保留密钥。",
                        allowableValues = {"keep", "replace", "remove"},
                        example = "keep",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                String keyAction,
        @Schema(
                        description = "读取配置时获得的最新 revision，用于防止过期页面覆盖新值。",
                        example = "从读取接口复制当前 revision",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                String revision,
        @Schema(
                        description = "对话推理等级；空字符串表示不发送，省略保留现值。支持程度由服务商决定。",
                        allowableValues = {
                            "", "none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra"
                        })
                String reasoningEffort) {
    /** 兼容不含推理等级的已有调用。 */
    public UpdateModelSettingsRequest(
            String provider,
            String model,
            String baseUrl,
            String apiKey,
            String keyAction,
            String revision) {
        this(provider, model, baseUrl, apiKey, keyAction, revision, null);
    }

    /** 输出请求的非敏感摘要，避免对象日志泄露 API 密钥。 */
    @Override
    public String toString() {
        return "ModelSettingsUpdate[redacted]";
    }
}
