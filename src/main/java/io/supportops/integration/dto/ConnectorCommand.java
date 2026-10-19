package io.supportops.integration.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 追加不可变连接修订，不修改已启用修订。 */
@Schema(description = "追加不可变连接修订，不修改已启用修订。")
public record ConnectorCommand(
        @Schema(description = "固定请求 UUID", requiredMode = Schema.RequiredMode.REQUIRED) String requestId,
        @Schema(description = "环境 UUID", requiredMode = Schema.RequiredMode.REQUIRED) String environmentId,
        @Schema(description = "STANDARD_REST；BUILTIN_DEMO 仅历史示例使用") String type,
        @Schema(description = "不可变连接策略", requiredMode = Schema.RequiredMode.REQUIRED) ConnectorConfig config,
        @Schema(description = "现场 Bearer 秘密，仅写入；每个修订使用独立凭据", accessMode = Schema.AccessMode.WRITE_ONLY) String bearerSecret,
        @Schema(description = "REPLACE写入、KEEP复制同环境已存秘密、CLEAR新草稿无秘密；省略为REPLACE") String secretAction,
        @Schema(description = "KEEP时指定同环境源修订UUID，不接受跨环境引用") String credentialSourceRevisionId) {
    /** 原调用省略秘密动作时仍要求写入新Bearer。 */
    public ConnectorCommand(String requestId, String environmentId, String type, ConnectorConfig config, String bearerSecret) {
        this(requestId, environmentId, type, config, bearerSecret, "REPLACE", null);
    }
    /** 禁止自动生成的 record 文本包含秘密。 */
    @Override public String toString() { return "ConnectorCommand[redacted]"; }
}
