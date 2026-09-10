package io.supportops.demo.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 人工选择示例故障条件；所有场景均使用自建的合成业务数据。 */
@Schema(name = "ScenarioRequest", description = "人工选择示例故障条件；所有场景均使用自建的合成业务数据。")
public record ScenarioRequest(
        @Schema(
                        description =
                                "故障场景：healthy 健康；unavailable 下游 503；credentials 凭据 401；migration"
                                        + " 配置迁移遗漏；stale-docs 旧版资料；insufficient 日志不可用；injection"
                                        + " 文档干扰测试。injection 不会自动上传攻击文档。",
                        allowableValues = {
                            "healthy",
                            "unavailable",
                            "credentials",
                            "migration",
                            "stale-docs",
                            "insufficient",
                            "injection"
                        },
                        example = "migration",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                String scenario) {}
