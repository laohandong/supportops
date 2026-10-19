package io.supportops.integration.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 固定只读协议载荷，不能携带可执行动作或任意地址。 */
public final class ProtocolData {
    /** 类型容器禁止实例化。 */
    private ProtocolData() {}
    /** 现场应用与其声明的能力。 */
    @Schema(description = "现场应用与其声明的能力。")
    public record AppInfoData(@Schema(description = "应用名称，最多160字符") String applicationName,
            @Schema(description = "现场实际支持的固定能力") List<String> capabilities) {}
    /** 源端批准公开的有效配置集合。 */
    @Schema(description = "源端批准公开的有效配置集合。")
    public record EffectiveConfigData(@Schema(description = "最多200项") List<ConfigItem> items) {}
    /** 配置秘密必须为空且脱敏标记为 true。 */
    @Schema(description = "配置秘密必须为空且脱敏标记为 true。")
    public record ConfigItem(@Schema(description = "配置键，最多128字符") String key,
            @Schema(description = "公开值，最多4096字符；脱敏时为空") String value,
            @Schema(description = "是否脱敏") boolean redacted,
            @Schema(description = "值来源标识，最多128字符") String valueSource) {}
    /** 有界日志记录，响应时间与日志发生时间独立。 */
    @Schema(description = "有界日志记录，响应时间与日志发生时间独立。")
    public record RecentLogsData(@Schema(description = "最多200条") List<LogEntry> entries,
            @Schema(description = "是否还有记录被截断") boolean truncated) {}
    /** 只包含批准文本的日志条目。 */
    @Schema(description = "只包含批准文本的日志条目。")
    public record LogEntry(@Schema(description = "事件发生时间 ISO-8601") String timestamp,
            @Schema(description = "TRACE、DEBUG、INFO、WARN、ERROR") String level,
            @Schema(description = "已脱敏消息，最多4096字符") String message,
            @Schema(description = "可为空的追踪标识，最多128字符") String traceId) {}
    /** 固定依赖健康集合，不含可访问地址。 */
    @Schema(description = "固定依赖健康集合，不含可访问地址。")
    public record DownstreamHealthData(@Schema(description = "最多50项") List<Dependency> dependencies) {}
    /** 依赖健康事实及安全失败原因。 */
    @Schema(description = "依赖健康事实及安全失败原因。")
    public record Dependency(@Schema(description = "依赖名称，最多160字符") String name,
            @Schema(description = "UP、DOWN、UNKNOWN") String status,
            @Schema(description = "健康观测时间 ISO-8601") String observedAt,
            @Schema(description = "可为空的 HTTP 状态码100至599") Integer responseCode,
            @Schema(description = "可为空的脱敏原因，最多512字符") String reason) {}
}
