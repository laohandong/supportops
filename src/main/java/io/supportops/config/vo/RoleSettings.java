package io.supportops.config.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import io.swagger.v3.oas.annotations.media.Schema;

/** 单个角色当前生效的配置及来源，不含密钥原文。 */
@Schema(name = "RoleSettings", description = "单个角色当前生效的配置及来源，不含密钥原文。")
@JsonInclude(Include.NON_NULL)
public record RoleSettings(
        @Schema(description = "服务商标识；包括内置预设和本地 YAML 中定义的自定义标识。", example = "deepseek")
                String provider,
        @Schema(
                        description = "接口协议；当前支持 openai-completions，禁用或未知服务商时可为空。",
                        example = "openai-completions")
                String api,
        @Schema(description = "实际选择的模型名称。", example = "deepseek-v4-flash") String model,
        @Schema(description = "凭据来源标识或环境变量名称，只包含名称、不包含值。", example = "DEEPSEEK_API_KEY")
                String credentialEnv,
        @Schema(description = "本地路由和密钥是否完整；读取该字段不会发起模型请求。", example = "false") boolean configured,
        @Schema(
                        description =
                                "配置错误码；正常时为空。常见值为"
                                    + " MODEL_NOT_CONFIGURED、EMBEDDING_NOT_CONFIGURED、EMBEDDING_DISABLED。",
                        example = "MODEL_NOT_CONFIGURED")
                String error,
        @Schema(description = "经过校验的基础地址；地址或其他配置无效时可能省略。", example = "https://api.deepseek.com")
                String baseUrl,
        @Schema(description = "是否使用界面保存的配置覆盖值。") boolean saved,
        @Schema(description = "是否存在密钥，不代表已经通过鉴权。") boolean hasKey,
        @Schema(description = "当前对话推理等级；空字符串表示不发送，向量角色为空。") String reasoningEffort) {}
