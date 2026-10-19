package io.supportops.integration.vo;

import io.supportops.integration.dto.ConnectorConfig;
import io.swagger.v3.oas.annotations.media.Schema;

/** 管理员连接描述，不包含可恢复秘密。 */
@Schema(description = "管理员连接描述，不包含可恢复秘密。")
public record ConnectorView(
        @Schema(description = "修订 UUID") String id,
        @Schema(description = "环境 UUID") String environmentId,
        @Schema(description = "环境内修订号") int revision,
        @Schema(description = "连接器类型") String type,
        @Schema(description = "管理端网络及能力策略") ConnectorConfig config,
        @Schema(description = "凭据 UUID，原文不可读取") String credentialId,
        @Schema(description = "DRAFT、ENABLED 或 DISABLED") String status,
        @Schema(description = "NOT_TESTED、PASSED、FAILED 或 STALE") String testStatus,
        @Schema(description = "最近测试完成时间") String testedAt,
        @Schema(description = "受控失败代码") String errorCode) {}
