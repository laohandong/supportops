package io.supportops.integration.service.support;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.MapperFeature;
import io.supportops.integration.vo.ProtocolData.AppInfoData;
import io.supportops.integration.vo.ProtocolData.ConfigItem;
import io.supportops.integration.vo.ProtocolData.Dependency;
import io.supportops.integration.vo.ProtocolData.DownstreamHealthData;
import io.supportops.integration.vo.ProtocolData.EffectiveConfigData;
import io.supportops.integration.vo.ProtocolData.LogEntry;
import io.supportops.integration.vo.ProtocolData.RecentLogsData;
import io.supportops.integration.vo.SourceEnvelope;
import io.supportops.project.vo.TargetView;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 在模型外发之前核对固定协议、现场身份、时间及能力载荷。 */
@Component
public class SourceProtocol {
    public static final Set<String> CAPABILITIES = Set.of("APP_INFO", "EFFECTIVE_CONFIG", "RECENT_LOGS", "DOWNSTREAM_HEALTH");
    private final ObjectMapper json;
    /** 使用独立严格解码器，不改变已有接口的 JSON 兼容行为。 */
    public SourceProtocol(ObjectMapper json) {
        this.json = json.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.json.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(20).build());
        this.json.disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    }
    /** 不可信的控制字段或身份漂移都不得进入模型。 */
    public SourceEnvelope validate(byte[] bytes, String requestId, TargetView target, String capability, int logLimit) {
        try {
            JsonNode root = json.readTree(bytes);
            fields(root, "protocolVersion", "requestId", "target", "observedAt", "source", "data");
            fields(root.get("target"), "systemCode", "environmentCode", "productKey", "productVersion");
            fields(root.get("source"), "kind", "instanceId");
            SourceEnvelope envelope = json.treeToValue(root, SourceEnvelope.class);
            SourceEnvelope.Identity identity = envelope.target();
            if (!"1.0".equals(envelope.protocolVersion()) || !requestId.equals(envelope.requestId())) {
                throw IntegrationInputs.error(502, "TARGET_PROTOCOL_MISMATCH");
            }
            if (!target.systemCode().equals(identity.systemCode()) || !target.environmentCode().equals(identity.environmentCode())
                    || !target.productKey().equals(identity.productKey()) || !target.productVersion().equals(identity.productVersion())) {
                throw IntegrationInputs.error(502, "TARGET_IDENTITY_MISMATCH");
            }
            observed(envelope.observedAt());
            if (!"APPLICATION".equals(envelope.source().kind())) { throw new IllegalArgumentException(); }
            IntegrationInputs.text(envelope.source().instanceId(), 128);
            JsonNode data = envelope.data();
            switch (capability) {
                case "APP_INFO" -> app(data);
                case "EFFECTIVE_CONFIG" -> config(data);
                case "RECENT_LOGS" -> logs(data, logLimit);
                case "DOWNSTREAM_HEALTH" -> health(data);
                default -> throw new IllegalArgumentException();
            }
            return envelope;
        } catch (org.springframework.web.server.ResponseStatusException exception) {
            if (exception.getStatusCode().value() == 400) { throw IntegrationInputs.error(502, "TARGET_SCHEMA_INVALID"); }
            throw exception;
        } catch (Exception exception) {
            throw IntegrationInputs.error(502, "TARGET_SCHEMA_INVALID");
        }
    }
    /** 应用能力须有必需信息，未知枚举不能变为动态工具。 */
    private void app(JsonNode data) throws Exception {
        fields(data, "applicationName", "capabilities");
        AppInfoData value = json.treeToValue(data, AppInfoData.class);
        IntegrationInputs.text(value.applicationName(), 160);
        if (value.capabilities() == null || value.capabilities().size() > 4 || !value.capabilities().contains("APP_INFO")
                || !CAPABILITIES.containsAll(value.capabilities()) || new HashSet<>(value.capabilities()).size() != value.capabilities().size()) {
            throw new IllegalArgumentException();
        }
    }
    /** 配置秘密必须已脱敏；协议禁止秘密值与可用值并存。 */
    private void config(JsonNode data) throws Exception {
        fields(data, "items");
        array(data.get("items"), 200);
        for (JsonNode item : data.get("items")) { fields(item, "key", "value", "redacted", "valueSource"); }
        EffectiveConfigData value = json.treeToValue(data, EffectiveConfigData.class);
        for (ConfigItem item : value.items()) {
            IntegrationInputs.text(item.key(), 128);
            IntegrationInputs.text(item.valueSource(), 128);
            boolean sensitive = item.key().toLowerCase(java.util.Locale.ROOT).matches(".*(password|secret|token|api.?key|credential).*" );
            if ((item.redacted() && item.value() != null) || (sensitive && (!item.redacted() || item.value() != null))
                    || (!item.redacted() && item.value() == null) || (item.value() != null && item.value().length() > 4096)) {
                throw new IllegalArgumentException();
            }
        }
    }
    /** 日志发生时间独立校验，不能因历史日志超出观测时限而丢弃事实。 */
    private void logs(JsonNode data, int limit) throws Exception {
        fields(data, "entries", "truncated");
        array(data.get("entries"), Math.min(200, limit));
        for (JsonNode item : data.get("entries")) { fields(item, "timestamp", "level", "message", "traceId"); }
        RecentLogsData value = json.treeToValue(data, RecentLogsData.class);
        for (LogEntry entry : value.entries()) {
            Instant.parse(entry.timestamp());
            if (!List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR").contains(entry.level())) { throw new IllegalArgumentException(); }
            IntegrationInputs.text(entry.message(), 4096);
            if (entry.traceId() != null) { IntegrationInputs.text(entry.traceId(), 128); }
        }
    }
    /** 依赖响应只包含名称和状态，拒绝地址等额外控制字段。 */
    private void health(JsonNode data) throws Exception {
        fields(data, "dependencies");
        array(data.get("dependencies"), 50);
        for (JsonNode item : data.get("dependencies")) { fields(item, "name", "status", "observedAt", "responseCode", "reason"); }
        DownstreamHealthData value = json.treeToValue(data, DownstreamHealthData.class);
        for (Dependency entry : value.dependencies()) {
            IntegrationInputs.text(entry.name(), 160);
            if (!List.of("UP", "DOWN", "UNKNOWN").contains(entry.status())) { throw new IllegalArgumentException(); }
            observed(entry.observedAt());
            if (entry.responseCode() != null && (entry.responseCode() < 100 || entry.responseCode() > 599)) { throw new IllegalArgumentException(); }
            if (entry.reason() != null && entry.reason().length() > 512) { throw new IllegalArgumentException(); }
        }
    }
    /** 观测事实超过5分钟或未来超过1分钟时明确失效。 */
    private void observed(String timestamp) {
        Instant value = Instant.parse(timestamp);
        Instant now = Instant.now();
        if (value.isBefore(now.minusSeconds(300)) || value.isAfter(now.plusSeconds(60))) {
            throw IntegrationInputs.error(502, "TARGET_OBSERVATION_STALE");
        }
    }
    /** 要求结构完整且无未知字段。 */
    private void fields(JsonNode value, String... expected) {
        if (value == null || !value.isObject()) { throw new IllegalArgumentException(); }
        Set<String> actual = new HashSet<>();
        value.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(Set.of(expected))) { throw new IllegalArgumentException(); }
    }
    /** 集合数量必须由源端和平台共同限制。 */
    private void array(JsonNode value, int maximum) {
        if (value == null || !value.isArray() || value.size() > maximum) { throw new IllegalArgumentException(); }
    }
}
