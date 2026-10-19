package io.supportops.integration.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Set;

/** 应用资源与固定权限、环境授权的条件更新。 */
@Schema(description = "应用资源与固定权限、环境授权的条件更新。")
public record ApplicationCommand(
        @Schema(description = "固定请求 UUID", requiredMode = Schema.RequiredMode.REQUIRED) String requestId,
        @Schema(description = "创建时为空，更新时应用 UUID") String id,
        @Schema(description = "项目 UUID；创建后固定") String projectId,
        @Schema(description = "项目内唯一编码，创建后固定") String code,
        @Schema(description = "应用名称，最多128字符") String name,
        @Schema(description = "状态：ACTIVE可用、DISABLED停用、ARCHIVED归档") String status,
        @Schema(description = "DIAGNOSE、READ、CANCEL 的显式集合") Set<String> permissions,
        @Schema(description = "同项目环境 UUID 集合，最多200项") Set<String> environmentIds,
        @Schema(description = "更新前版本，创建时为空") Integer expectedVersion,
        @Schema(description = "每分钟提交额度，1至300；省略保留原值，创建默认30") Integer submitPerMinute,
        @Schema(description = "每分钟读取额度，1至1200；省略保留原值，创建默认120") Integer readPerMinute,
        @Schema(description = "未释放任务额度，1至4；省略保留原值，创建默认2") Integer maxActive) {
    /** 兼容未指定额度的管理调用，沿用现有或默认额度。 */
    public ApplicationCommand(String requestId, String id, String projectId, String code, String name,
            String status, Set<String> permissions, Set<String> environmentIds, Integer expectedVersion) {
        this(requestId, id, projectId, code, name, status, permissions, environmentIds, expectedVersion, null, null, null);
    }
}
