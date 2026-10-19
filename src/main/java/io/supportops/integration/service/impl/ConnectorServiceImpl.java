package io.supportops.integration.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.supportops.demo.service.DemoService;
import io.supportops.demo.vo.DemoConfiguration;
import io.supportops.demo.vo.DemoHealth;
import io.supportops.integration.dto.ConnectorAction;
import io.supportops.integration.dto.ConnectorCommand;
import io.supportops.integration.dto.ConnectorConfig;
import io.supportops.integration.entity.ConnectorRevisionEntity;
import io.supportops.integration.entity.ConnectorTestEntity;
import io.supportops.integration.mapper.ConnectorRevisionMapper;
import io.supportops.integration.mapper.ConnectorTestMapper;
import io.supportops.integration.service.ConnectorService;
import io.supportops.integration.service.CredentialService;
import io.supportops.integration.service.support.EnterpriseIds;
import io.supportops.integration.service.support.EnvironmentAdmission;
import io.supportops.integration.service.support.IntegrationInputs;
import io.supportops.integration.service.support.ManagementCommands;
import io.supportops.integration.service.support.PinnedHttpSource;
import io.supportops.integration.service.support.SourceProtocol;
import io.supportops.integration.vo.ConnectorView;
import io.supportops.integration.vo.ProtocolData.AppInfoData;
import io.supportops.integration.vo.ProtocolData.ConfigItem;
import io.supportops.integration.vo.ProtocolData.Dependency;
import io.supportops.integration.vo.ProtocolData.DownstreamHealthData;
import io.supportops.integration.vo.ProtocolData.EffectiveConfigData;
import io.supportops.integration.vo.ProtocolData.LogEntry;
import io.supportops.integration.vo.ProtocolData.RecentLogsData;
import io.supportops.integration.vo.SourceEnvelope;
import io.supportops.project.service.ProjectService;
import io.supportops.project.vo.TargetView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 修订不可变，网络测试位于事务外；只有安全状态进入测试回执。 */
@Service
public class ConnectorServiceImpl implements ConnectorService {
    private io.supportops.integration.service.support.RestoreGeneration restoreGeneration;
    /** 连接测试批准也属于部署恢复代次，恢复后不能沿用旧测试。 */
    @org.springframework.beans.factory.annotation.Autowired
    public void restoreGeneration(io.supportops.integration.service.support.RestoreGeneration value) { restoreGeneration = value; }
    private final ConnectorRevisionMapper revisions;
    private final ConnectorTestMapper tests;
    private final ProjectService projects;
    private final CredentialService credentials;
    private final ManagementCommands commands;
    private final EnvironmentAdmission admission;
    private final PinnedHttpSource http;
    private final SourceProtocol protocol;
    private final ObjectMapper json;
    private final DemoService demo;
    private final boolean enterprise;
    /** 测试回执只含身份摘要及错误代码，禁止保存采集到的日志和配置值。 */
    private record TestReceipt(String targetDigest, Set<String> capabilities, String errorCode) {}
    /** 注入修订存储与明确的跨模块接口。 */
    public ConnectorServiceImpl(ConnectorRevisionMapper revisions, ConnectorTestMapper tests, ProjectService projects,
            CredentialService credentials, ManagementCommands commands, EnvironmentAdmission admission,
            PinnedHttpSource http, SourceProtocol protocol, ObjectMapper json, DemoService demo,
            @Value("${supportops.enterprise.enabled:false}") boolean enterprise) {
        this.revisions = revisions; this.tests = tests; this.projects = projects; this.credentials = credentials;
        this.commands = commands; this.admission = admission; this.http = http; this.protocol = protocol;
        this.json = json; this.demo = demo; this.enterprise = enterprise;
    }
    /** 管理端保留草稿和失败历史，界面不会把已配置描述为已通过。 */
    @Override
    public List<ConnectorView> list(String environmentId) {
        ManagementCommands.administrator();
        String id = IntegrationInputs.uuid(environmentId);
        return revisions.selectList(Wrappers.<ConnectorRevisionEntity>lambdaQuery().eq(ConnectorRevisionEntity::getEnvironmentId, id)
                .orderByDesc(ConnectorRevisionEntity::getRevision)).stream().map(this::view).toList();
    }
    /** 同键重放不再次写入秘密，草稿可在诊断执行时保存。 */
    @Override
    public ConnectorView create(ConnectorCommand input) {
        return commands.execute(input.requestId(), "CONNECTOR_CREATE", input, ConnectorView.class, () -> {
            TargetView target = projects.resolve(input.environmentId(), null, true);
            if (!"STANDARD_REST".equals(input.type()) || input.config() == null
                    || (enterprise && input.config().allowLocalHttp())) {
                throw IntegrationInputs.error(400, "INVALID_CONNECTOR_TYPE_OR_POLICY");
            }
            http.validate(input.config());
            ConnectorRevisionEntity latest = revisions.selectOne(Wrappers.<ConnectorRevisionEntity>lambdaQuery()
                    .eq(ConnectorRevisionEntity::getEnvironmentId, target.environmentId())
                    .orderByDesc(ConnectorRevisionEntity::getRevision).last("LIMIT 1"));
            int revision = latest == null ? 1 : latest.getRevision() + 1;
            ConnectorRevisionEntity value = new ConnectorRevisionEntity();
            value.setId(UUID.randomUUID().toString()); value.setEnvironmentId(target.environmentId()); value.setRevision(revision);
            value.setType("STANDARD_REST"); value.setConfigJson(encode(input.config()));
            String action = input.secretAction() == null ? "REPLACE" : input.secretAction();
            String secret;
            switch (action) {
                case "REPLACE" -> secret = input.bearerSecret();
                case "KEEP" -> {
                    ConnectorRevisionEntity previous = revision(input.credentialSourceRevisionId());
                    if (!target.environmentId().equals(previous.getEnvironmentId())) {
                        throw IntegrationInputs.error(400, "CREDENTIAL_SCOPE_MISMATCH");
                    }
                    if (previous.getCredentialId() == null) { throw IntegrationInputs.error(409, "TARGET_CREDENTIAL_MISSING"); }
                    secret = credentials.reveal(previous.getCredentialId(), "CONNECTOR", target.environmentId(), previous.getRevision());
                }
                case "CLEAR" -> secret = null;
                default -> throw IntegrationInputs.error(400, "INVALID_SECRET_ACTION");
            }
            value.setCredentialId("CLEAR".equals(action) ? null : credentials.store("CONNECTOR", target.environmentId(), revision, secret));
            value.setDigest(IntegrationInputs.digest(value.getConfigJson())); value.setCreatedAt(Instant.now().toString());
            revisions.insert(value);
            return view(value);
        });
    }
    /** 测试固定批准能力，日志最多5条；网络不包入数据库事务。 */
    @Override
    public synchronized ConnectorView test(ConnectorAction input) {
        ManagementCommands.administrator();
        java.util.Optional<ConnectorView> replay = commands.existing(input.requestId(), "CONNECTOR_TEST", input, ConnectorView.class);
        if (replay.isPresent()) { return replay.get(); }
        ConnectorRevisionEntity revision = revision(input.revisionId());
        TargetView target = projects.resolve(revision.getEnvironmentId(), null, true);
        ConnectorConfig policy = policy(revision.getId());
        String started = Instant.now().toString();
        String error = "";
        try {
            SourceEnvelope info = read(revision.getId(), target, "APP_INFO", null, 5);
            Set<String> available = new java.util.HashSet<>();
            info.data().get("capabilities").forEach(value -> available.add(value.asText()));
            if (!available.containsAll(policy.capabilities())) { throw IntegrationInputs.error(502, "TARGET_CAPABILITY_MISSING"); }
            for (String capability : policy.capabilities()) {
                if (!"APP_INFO".equals(capability)) { read(revision.getId(), target, capability, null, 5); }
            }
        } catch (org.springframework.web.server.ResponseStatusException exception) {
            error = exception.getReason();
        }
        String finalError = error;
        return commands.execute(input.requestId(), "CONNECTOR_TEST", input, ConnectorView.class, () -> {
            ConnectorTestEntity result = new ConnectorTestEntity();
            result.setId(UUID.randomUUID().toString()); result.setRevisionId(revision.getId());
            result.setStatus(finalError.isEmpty() ? "PASSED" : "FAILED");
            result.setResultJson(encode(new TestReceipt(targetDigest(target), policy.capabilities(), finalError)));
            result.setStartedAt(started); result.setFinishedAt(Instant.now().toString()); tests.insert(result);
            return view(revision);
        });
    }
    /** 激活需要相同身份的最近成功测试，完整事务结束前持续持有环境锁。 */
    @Override
    public ConnectorView activate(ConnectorAction input) {
        ManagementCommands.administrator();
        ConnectorRevisionEntity revision = revision(input.revisionId());
        return admission.environment(revision.getEnvironmentId(), () -> commands.execute(input.requestId(), "CONNECTOR_ACTIVATE",
                input, ConnectorView.class, () -> {
                    TargetView target = projects.resolve(revision.getEnvironmentId(), null, true);
                    ConnectorTestEntity test = latestTest(revision.getId());
                    if (input.expectedVersion() == null || test == null || !"PASSED".equals(test.getStatus())
                            || Instant.parse(test.getFinishedAt()).isBefore(Instant.now().minusSeconds(1800))
                            || !decode(test.getResultJson(), TestReceipt.class).targetDigest().equals(targetDigest(target))) {
                        throw IntegrationInputs.error(409, "CONNECTOR_TEST_REQUIRED");
                    }
                    projects.activate(target.environmentId(), revision.getId(), input.expectedVersion());
                    return new ConnectorView(revision.getId(), target.environmentId(), revision.getRevision(), revision.getType(),
                            policy(revision.getId()), revision.getCredentialId(), "ENABLED", "PASSED", test.getFinishedAt(), "");
                }));
    }
    /** 私有连接策略只能交给执行边界，模型仅获取能力和安全目标。 */
    @Override
    public ConnectorConfig policy(String revisionId) { return decode(revision(revisionId).getConfigJson(), ConnectorConfig.class); }
    /** 使用不可变修订和固定身份访问，每次均重新核对凭据撤销。 */
    @Override
    public SourceEnvelope read(String revisionId, TargetView target, String capability, String runId, int logLimit) {
        ConnectorRevisionEntity revision = revision(revisionId);
        if (!target.environmentId().equals(revision.getEnvironmentId()) || logLimit < 1 || logLimit > 200) {
            throw IntegrationInputs.error(400, "INVALID_CONNECTOR_SCOPE");
        }
        String requestId = UUID.randomUUID().toString();
        ConnectorConfig config = policy(revisionId);
        if (!config.capabilities().contains(capability)) { throw IntegrationInputs.error(403, "CAPABILITY_NOT_GRANTED"); }
        if ("BUILTIN_DEMO".equals(revision.getType()) && EnterpriseIds.ENVIRONMENT.equals(target.environmentId())) {
            return builtin(target, capability, requestId, logLimit);
        }
        if (revision.getCredentialId() == null) { throw IntegrationInputs.error(503, "TARGET_CREDENTIAL_MISSING"); }
        String secret = credentials.reveal(revision.getCredentialId(), "CONNECTOR", target.environmentId(), revision.getRevision());
        String query = "RECENT_LOGS".equals(capability) ? "?from=" + Instant.now().minusSeconds(900) + "&to=" + Instant.now() + "&limit=" + logLimit : "";
        byte[] body = http.get(config, capability, secret, requestId, runId, query);
        SourceEnvelope result = protocol.validate(body, requestId, target, capability, logLimit);
        // 即使源端遗漏脱敏，平台也禁止自己的目标 Bearer 出现在持久化事实或模型资料中。
        if (result.data().toString().contains(secret)) { throw IntegrationInputs.error(502, "TARGET_SECRET_EXPOSED"); }
        return result;
    }
    /** 历史示例适配仍读取真实示例服务，不依赖企业网络策略。 */
    private SourceEnvelope builtin(TargetView target, String capability, String requestId, int limit) {
        JsonNode data;
        switch (capability) {
            case "APP_INFO" -> {
                if (!target.productVersion().equals(demo.appInfo().version())) { throw IntegrationInputs.error(502, "TARGET_IDENTITY_MISMATCH"); }
                data = json.valueToTree(new AppInfoData(demo.appInfo().application(), SourceProtocol.CAPABILITIES.stream().sorted().toList()));
            }
            case "EFFECTIVE_CONFIG" -> {
                DemoConfiguration config = demo.config();
                List<ConfigItem> items = new ArrayList<>();
                config.provided().forEach((key, value) -> items.add(new ConfigItem(key, value, false, "provided")));
                config.effective().forEach((key, value) -> items.add(new ConfigItem(key, value, false, "effective")));
                items.add(new ConfigItem("credential", null, true, config.credentialConfigured() ? "configured" : "missing"));
                data = json.valueToTree(new EffectiveConfigData(items));
            }
            case "RECENT_LOGS" -> data = json.valueToTree(new RecentLogsData(demo.logs().entries().stream().limit(limit)
                    .map(entry -> new LogEntry(entry.timestamp(), "INFO", entry.toString(), null)).toList(), false));
            case "DOWNSTREAM_HEALTH" -> {
                DemoHealth health = demo.health();
                data = json.valueToTree(new DownstreamHealthData(List.of(new Dependency("demo-downstream",
                        health.httpStatus() == 200 ? "UP" : "DOWN", health.observedAt(), health.httpStatus(), health.body().toString()))));
            }
            default -> throw IntegrationInputs.error(403, "CAPABILITY_NOT_GRANTED");
        }
        return new SourceEnvelope("1.0", requestId, new SourceEnvelope.Identity(target.systemCode(), target.environmentCode(),
                target.productKey(), target.productVersion()), Instant.now().toString(), new SourceEnvelope.Source("APPLICATION", "builtin-demo"), data);
    }
    /** 修订不存在时使用统一错误，避免返回数据库详情。 */
    private ConnectorRevisionEntity revision(String id) {
        ConnectorRevisionEntity value = revisions.selectById(IntegrationInputs.uuid(id));
        if (value == null) { throw IntegrationInputs.error(404, "CONNECTOR_NOT_FOUND"); }
        return value;
    }
    /** 读取最近测试，保留所有早期失败事实。 */
    private ConnectorTestEntity latestTest(String id) {
        return tests.selectOne(Wrappers.<ConnectorTestEntity>lambdaQuery().eq(ConnectorTestEntity::getRevisionId, id)
                .orderByDesc(ConnectorTestEntity::getFinishedAt).orderByDesc(ConnectorTestEntity::getId).last("LIMIT 1"));
    }
    /** 管理描述使用当前启用指针和安全测试状态。 */
    private ConnectorView view(ConnectorRevisionEntity value) {
        ConnectorTestEntity test = latestTest(value.getId());
        String status = "DRAFT";
        TargetView current = null;
        try {
            current = projects.resolve(value.getEnvironmentId(), null, true);
            if (value.getId().equals(current.connectorRevisionId())) { status = "ENABLED"; }
        } catch (org.springframework.web.server.ResponseStatusException exception) { status = "DISABLED"; }
        String testStatus = test == null ? "NOT_TESTED" : test.getStatus();
        if (test != null && "PASSED".equals(testStatus) && (current == null
                || !decode(test.getResultJson(), TestReceipt.class).targetDigest().equals(targetDigest(current))
                || Instant.parse(test.getFinishedAt()).isBefore(Instant.now().minusSeconds(1800)))) {
            testStatus = "STALE";
        }
        return new ConnectorView(value.getId(), value.getEnvironmentId(), value.getRevision(), value.getType(), policy(value.getId()),
                value.getCredentialId(), status, testStatus, test == null ? null : test.getFinishedAt(),
                test == null ? "" : decode(test.getResultJson(), TestReceipt.class).errorCode());
    }
    /** 身份摘要排除可轮换的连接指针和显示名称。 */
    private String targetDigest(TargetView value) {
        return IntegrationInputs.digest(String.join("\n", value.systemId(), value.environmentId(), value.systemCode(),
                value.environmentCode(), value.productKey(), value.productVersion(),
                Integer.toString(restoreGeneration == null ? 0 : restoreGeneration.current())));
    }
    /** 序列化固定业务结构。 */
    private String encode(Object value) {
        try { return json.writeValueAsString(value); } catch (Exception exception) { throw new IllegalStateException("CONNECTOR_ENCODING_FAILED"); }
    }
    /** 从数据库恢复固定类型，损坏时阻止执行。 */
    private <T> T decode(String value, Class<T> type) {
        try { return json.readValue(value, type); } catch (Exception exception) { throw new IllegalStateException("CONNECTOR_CONFIG_INVALID"); }
    }
}
