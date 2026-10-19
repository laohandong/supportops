package io.supportops.demo.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/** 与提案无关的场景审计事实；跨实例历史不能用于证明当前修复成功。 */
@Schema(description = "合成环境人工场景变更审计，始终保留原实例和原修订")
public record DemoEnvironmentEvent(
        @Schema(description = "递增游标") long id,
        @Schema(description = "服务端捕获的操作账号") String actorId,
        @Schema(description = "固定目标BUILTIN_ORDERBRIDGE") String targetId,
        @Schema(description = "原环境实例UUID") String instanceId,
        @Schema(description = "事件类型SCENARIO_RESET") String kind,
        @Schema(description = "变更前场景") String scenarioBefore,
        @Schema(description = "请求切换的场景") String scenarioAfter,
        @Schema(description = "变更前修订") long beforeRevision,
        @Schema(description = "读回修订；未知为空") Long afterRevision,
        @Schema(description = "脱敏变更前事实JSON") String beforeJson,
        @Schema(description = "脱敏读回事实及同步结果JSON；未保存为空") String afterJson,
        @Schema(description = "STARTED已登记、APPLIED确认应用、UNKNOWN结果不明") String status,
        @Schema(description = "稳定错误码；确认成功为空串") String errorCode,
        @Schema(description = "受理时间ISO8601 UTC") String startedAt,
        @Schema(description = "收尾时间ISO8601 UTC；未保存为空") String finishedAt) {}
