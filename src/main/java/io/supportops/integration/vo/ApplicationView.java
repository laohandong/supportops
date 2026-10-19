package io.supportops.integration.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Set;

/** 应用公开状态与授权，不包含秘密摘要。 */
@Schema(description = "应用公开状态与授权，不包含秘密摘要。")
public record ApplicationView(@Schema(description = "应用 UUID") String id,
        @Schema(description = "所属项目 UUID") String projectId,
        @Schema(description = "应用编码") String code,
        @Schema(description = "应用名称") String name,
        @Schema(description = "状态：ACTIVE可用、DISABLED停用、ARCHIVED归档") String status,
        @Schema(description = "旧版本条件更新号") int version,
        @Schema(description = "当前固定权限") Set<String> permissions,
        @Schema(description = "授权环境 UUID") Set<String> environmentIds,
        @Schema(description = "每分钟提交额度") int submitPerMinute,
        @Schema(description = "每分钟读取额度") int readPerMinute,
        @Schema(description = "未释放任务额度") int maxActive) {}
