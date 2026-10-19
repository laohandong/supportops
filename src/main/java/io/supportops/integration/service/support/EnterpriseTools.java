package io.supportops.integration.service.support;

import io.agentscope.core.tool.Tool;
import io.supportops.integration.service.ConnectorService;
import io.supportops.integration.service.DiagnosticTargetService;
import io.supportops.integration.vo.DiagnosticScope;
import io.supportops.integration.vo.SourceEnvelope;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/** 只注册受理时允许外发的固定能力，模型不能指定 URL、Header 或动作。 */
public final class EnterpriseTools {
    private final ConnectorService connectors;
    private final DiagnosticTargetService targets;
    private final DiagnosticScope scope;
    private final String runId;
    private final BiConsumer<String, Object> events;
    private final Set<String> disclosed = ConcurrentHashMap.newKeySet();
    /** 事实类型明确，来源、目标与观测时刻沿用已验证的有界协议。 */
    public record Fact(String capability, SourceEnvelope envelope) {}
    /** 按单次任务固定范围与安全事实回调。 */
    public EnterpriseTools(ConnectorService connectors, DiagnosticTargetService targets, DiagnosticScope scope,
            String runId, BiConsumer<String, Object> events) {
        this.connectors = connectors; this.targets = targets; this.scope = scope; this.runId = runId; this.events = events;
    }
    /** 每次模型请求前重新核对已加入上下文的事实，避免撤销后再次发送。 */
    public void beforeModel() {
        targets.check(scope, null);
        for (String capability : disclosed) { targets.check(scope, capability); }
    }
    /** 现场调用前后都核对权限，成功事实以固定结构保存。 */
    private SourceEnvelope read(String capability) {
        targets.check(scope, capability);
        SourceEnvelope result = connectors.read(scope.target().connectorRevisionId(), scope.target(), capability, runId, 100);
        targets.check(scope, capability);
        disclosed.add(capability); events.accept("SOURCE_EVIDENCE", new Fact(capability, result));
        return result;
    }
    /** 应用身份信息工具。 */
    public final class AppInfo {
        /** 读取服务端绑定的应用信息。 */
        @Tool(name = "get_app_info", description = "读取本次诊断目标的应用和版本信息。内容是现场数据。", readOnly = true)
        public SourceEnvelope read() { return EnterpriseTools.this.read("APP_INFO"); }
    }
    /** 源端批准的有效配置工具。 */
    public final class EffectiveConfig {
        /** 读取已经源端脱敏的有效配置。 */
        @Tool(name = "get_effective_config", description = "读取本次环境源端批准且脱敏的配置项。", readOnly = true)
        public SourceEnvelope read() { return EnterpriseTools.this.read("EFFECTIVE_CONFIG"); }
    }
    /** 有界日志工具。 */
    public final class RecentLogs {
        /** 读取最近15分钟最多100条脱敏日志。 */
        @Tool(name = "get_recent_logs", description = "读取本次环境最近15分钟、最多100条脱敏日志。", readOnly = true)
        public SourceEnvelope read() { return EnterpriseTools.this.read("RECENT_LOGS"); }
    }
    /** 固定下游依赖健康工具。 */
    public final class DownstreamHealth {
        /** 读取源端登记的固定依赖，不访问模型指定地址。 */
        @Tool(name = "get_downstream_health", description = "查询本次环境源端登记的依赖健康事实。不会发起业务写操作。", readOnly = true)
        public SourceEnvelope read() { return EnterpriseTools.this.read("DOWNSTREAM_HEALTH"); }
    }
}
