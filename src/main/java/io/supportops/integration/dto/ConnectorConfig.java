package io.supportops.integration.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Set;

/** 不可变连接策略；地址仅供管理端使用，不进入模型上下文。 */
@Schema(description = "不可变连接策略；地址仅供管理端使用，不进入模型上下文。")
public record ConnectorConfig(
        @Schema(description = "HTTPS 基础地址，无查询、片段或用户信息") String baseUrl,
        @Schema(description = "管理员批准的精确 IPv4/IPv6 地址，最多16项") Set<String> allowedAddresses,
        @Schema(description = "固定能力：APP_INFO、EFFECTIVE_CONFIG、RECENT_LOGS、DOWNSTREAM_HEALTH") Set<String> capabilities,
        @Schema(description = "允许发送模型的能力子集；默认仅应用信息和健康") Set<String> modelData,
        @Schema(description = "仅合成回环样例允许明文 HTTP；企业入口禁止开启") boolean allowLocalHttp) {}
