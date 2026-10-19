package io.supportops.integration.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.supportops.integration.dto.AccessKeyCommand;
import io.supportops.integration.dto.ApplicationCommand;
import io.supportops.integration.dto.RevokeKeyCommand;
import io.supportops.integration.entity.ApplicationAccessKeyEntity;
import io.supportops.integration.entity.ApplicationEnvironmentGrantEntity;
import io.supportops.integration.entity.IntegrationApplicationEntity;
import io.supportops.integration.mapper.ApplicationAccessKeyMapper;
import io.supportops.integration.mapper.ApplicationEnvironmentGrantMapper;
import io.supportops.integration.mapper.IntegrationApplicationMapper;
import io.supportops.integration.service.ApplicationService;
import io.supportops.integration.service.support.IntegrationInputs;
import io.supportops.integration.service.support.ManagementCommands;
import io.supportops.integration.service.support.RestoreGeneration;
import io.supportops.integration.vo.AccessKeyIssuance;
import io.supportops.integration.vo.AccessKeyView;
import io.supportops.integration.vo.ApplicationPrincipal;
import io.supportops.integration.vo.ApplicationView;
import io.supportops.project.service.ProjectService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 保存访问密钥摘要及显式应用授权，所有运行权限均查当前事实。 */
@Service
public class ApplicationServiceImpl implements ApplicationService {
    private static final Set<String> PERMISSIONS = Set.of("DIAGNOSE", "READ", "CANCEL");
    private final IntegrationApplicationMapper applications;
    private final ApplicationAccessKeyMapper keys;
    private final ApplicationEnvironmentGrantMapper grants;
    private final ProjectService projects;
    private final ManagementCommands commands;
    private final ObjectMapper json;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, int[]> rates = new HashMap<>();
    private final Map<String, Integer> active = new HashMap<>();
    private RestoreGeneration restoreGeneration;
    /** 注入恢复代次；旧独立测试保留初始代次。 */
    @Autowired
    public void restoreGeneration(RestoreGeneration value) { restoreGeneration = value; }
    /** 未进行恢复时使用代次零。 */
    private int generation() { return restoreGeneration == null ? 0 : restoreGeneration.current(); }
    /** 注入应用自己的存储边界及项目关系服务。 */
    public ApplicationServiceImpl(IntegrationApplicationMapper applications, ApplicationAccessKeyMapper keys,
            ApplicationEnvironmentGrantMapper grants, ProjectService projects, ManagementCommands commands, ObjectMapper json) {
        this.applications = applications; this.keys = keys; this.grants = grants; this.projects = projects;
        this.commands = commands; this.json = json;
    }
    /** 管理员列出全部应用状态。 */
    @Override public List<ApplicationView> list() {
        ManagementCommands.administrator();
        return applications.selectList(Wrappers.<IntegrationApplicationEntity>lambdaQuery()
                .orderByAsc(IntegrationApplicationEntity::getCode)).stream().map(this::view).toList();
    }
    /** 授权、状态及管理回执一次提交；归属不能变更。 */
    @Override public ApplicationView save(ApplicationCommand input) {
        try {
            return commands.execute(input.requestId(), "APPLICATION_SAVE", input, ApplicationView.class, () -> {
                if (input.permissions() == null || !PERMISSIONS.containsAll(input.permissions()) || input.environmentIds() == null
                        || input.environmentIds().size() > 200 || input.status() == null
                        || !Set.of("ACTIVE", "DISABLED", "ARCHIVED").contains(input.status())) {
                    throw IntegrationInputs.error(400, "INVALID_APPLICATION_POLICY");
                }
                String projectId = IntegrationInputs.uuid(input.projectId());
                if (projects.resources().stream().noneMatch(value -> value.id().equals(projectId) && "PROJECT".equals(value.kind())
                        && "ACTIVE".equals(value.status()))) { throw IntegrationInputs.error(404, "PROJECT_NOT_AVAILABLE"); }
                for (String environment : input.environmentIds()) {
                    if (!projectId.equals(projects.resolve(environment, null, true).projectId())) {
                        throw IntegrationInputs.error(400, "APPLICATION_PROJECT_MISMATCH");
                    }
                }
                IntegrationApplicationEntity value;
                if (input.id() == null) {
                    value = new IntegrationApplicationEntity(); value.setId(UUID.randomUUID().toString()); value.setProjectId(projectId);
                    value.setCode(IntegrationInputs.code(input.code())); value.setName(IntegrationInputs.text(input.name(), 128));
                    value.setStatus(input.status()); value.setPermissionsJson(encode(input.permissions().stream().sorted().toList()));
                    value.setSubmitPerMinute(30); value.setReadPerMinute(120); value.setMaxActive(2); value.setVersion(1);
                    value.setSubmitPerMinute(quota(input.submitPerMinute(), 30, 300));
                    value.setReadPerMinute(quota(input.readPerMinute(), 120, 1200));
                    value.setMaxActive(quota(input.maxActive(), 2, 4));
                    value.setApprovedGeneration(generation());
                    applications.insert(value);
                } else {
                    value = application(IntegrationInputs.uuid(input.id()));
                    if (input.expectedVersion() == null || !value.getProjectId().equals(projectId) || "ARCHIVED".equals(value.getStatus())) {
                        throw IntegrationInputs.error(409, "APPLICATION_VERSION_CONFLICT");
                    }
                    int changed = applications.update(Wrappers.<IntegrationApplicationEntity>lambdaUpdate().eq(IntegrationApplicationEntity::getId, value.getId())
                            .eq(IntegrationApplicationEntity::getVersion, input.expectedVersion()).ne(IntegrationApplicationEntity::getStatus, "ARCHIVED")
                            .set(IntegrationApplicationEntity::getName, IntegrationInputs.text(input.name(), 128))
                            .set(IntegrationApplicationEntity::getStatus, input.status())
                            .set(IntegrationApplicationEntity::getPermissionsJson, encode(input.permissions().stream().sorted().toList()))
                            .set(IntegrationApplicationEntity::getSubmitPerMinute, quota(input.submitPerMinute(), value.getSubmitPerMinute(), 300))
                            .set(IntegrationApplicationEntity::getReadPerMinute, quota(input.readPerMinute(), value.getReadPerMinute(), 1200))
                            .set(IntegrationApplicationEntity::getMaxActive, quota(input.maxActive(), value.getMaxActive(), 4))
                            .set(IntegrationApplicationEntity::getApprovedGeneration, generation())
                            .set(IntegrationApplicationEntity::getVersion, input.expectedVersion() + 1));
                    if (changed != 1) { throw IntegrationInputs.error(409, "APPLICATION_VERSION_CONFLICT"); }
                    grants.delete(Wrappers.<ApplicationEnvironmentGrantEntity>lambdaQuery().eq(ApplicationEnvironmentGrantEntity::getApplicationId, value.getId()));
                }
                for (String environment : input.environmentIds()) {
                    ApplicationEnvironmentGrantEntity grant = new ApplicationEnvironmentGrantEntity(); grant.setId(UUID.randomUUID().toString());
                    grant.setApplicationId(value.getId()); grant.setEnvironmentId(IntegrationInputs.uuid(environment)); grants.insert(grant);
                }
                return view(application(value.getId()));
            });
        } catch (DuplicateKeyException exception) { throw IntegrationInputs.error(409, "APPLICATION_CODE_EXISTS"); }
    }
    /** 明文只在本次成功提交的内存中保留，回执永远不含秘密。 */
    @Override public AccessKeyIssuance issue(AccessKeyCommand input) {
        AtomicReference<String> oneTime = new AtomicReference<>();
        AccessKeyView result = commands.execute(input.requestId(), "APPLICATION_KEY_ISSUE", input, AccessKeyView.class, () -> {
            IntegrationApplicationEntity application = application(IntegrationInputs.uuid(input.applicationId()));
            boolean approved = Objects.equals(application.getApprovedGeneration(), generation());
            if (!"ACTIVE".equals(application.getStatus()) || !approved) {
                throw IntegrationInputs.error(409, "APPLICATION_DISABLED");
            }
            int days = input.expiresInDays() == null ? 30 : input.expiresInDays();
            if (days < 1 || days > 90) { throw IntegrationInputs.error(400, "INVALID_KEY_EXPIRY"); }
            List<ApplicationAccessKeyEntity> live = keys.selectList(Wrappers.<ApplicationAccessKeyEntity>lambdaQuery()
                    .eq(ApplicationAccessKeyEntity::getApplicationId, application.getId()).eq(ApplicationAccessKeyEntity::getStatus, "ACTIVE"))
                    .stream().filter(value -> Objects.equals(value.getApprovedGeneration(), generation())
                            && Instant.parse(value.getExpiresAt()).isAfter(Instant.now())).toList();
            if (live.size() >= 2) { throw IntegrationInputs.error(409, "KEY_LIMIT_REACHED"); }
            if (input.rotate()) {
                for (ApplicationAccessKeyEntity previous : live) {
                    String expiry = Instant.parse(previous.getExpiresAt()).isAfter(Instant.now().plusSeconds(86400))
                            ? Instant.now().plusSeconds(86400).toString() : previous.getExpiresAt();
                    int changed = keys.update(Wrappers.<ApplicationAccessKeyEntity>lambdaUpdate().eq(ApplicationAccessKeyEntity::getId, previous.getId())
                            .eq(ApplicationAccessKeyEntity::getExpiresAt, previous.getExpiresAt()).set(ApplicationAccessKeyEntity::getExpiresAt, expiry));
                    if (changed != 1) { throw IntegrationInputs.error(409, "ACCESS_KEY_VERSION_CONFLICT"); }
                }
            }
            ApplicationAccessKeyEntity value = new ApplicationAccessKeyEntity(); value.setId(UUID.randomUUID().toString());
            byte[] entropy = new byte[32]; random.nextBytes(entropy);
            String secret = "sop_" + value.getId() + "_" + Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
            value.setApplicationId(application.getId()); value.setSecretHash(IntegrationInputs.digest(secret)); value.setStatus("ACTIVE");
            value.setExpiresAt(Instant.now().plusSeconds(days * 86400L).toString()); value.setCreatedBy(ManagementCommands.administrator());
            value.setApprovedGeneration(generation());
            value.setCreatedAt(Instant.now().toString()); keys.insert(value); oneTime.set(secret);
            return keyView(value);
        });
        return new AccessKeyIssuance(keyView(keys.selectById(result.id())), oneTime.get());
    }
    /** 读取只返回状态和时间，无法恢复原文或秘密摘要。 */
    @Override public List<AccessKeyView> keys(String applicationId) {
        ManagementCommands.administrator();
        return keys.selectList(Wrappers.<ApplicationAccessKeyEntity>lambdaQuery()
                .eq(ApplicationAccessKeyEntity::getApplicationId, IntegrationInputs.uuid(applicationId))
                .orderByDesc(ApplicationAccessKeyEntity::getCreatedAt)).stream().map(this::keyView).toList();
    }
    /** 撤销密钥与回执同事务，正在运行的任务下次权限检查立即失败。 */
    @Override public AccessKeyView revoke(RevokeKeyCommand input) {
        return commands.execute(input.requestId(), "APPLICATION_KEY_REVOKE", input, AccessKeyView.class, () -> {
            String id = IntegrationInputs.uuid(input.keyId());
            if (keys.selectById(id) == null) { throw IntegrationInputs.error(404, "ACCESS_KEY_NOT_FOUND"); }
            keys.update(Wrappers.<ApplicationAccessKeyEntity>lambdaUpdate().eq(ApplicationAccessKeyEntity::getId, id)
                    .eq(ApplicationAccessKeyEntity::getStatus, "ACTIVE").set(ApplicationAccessKeyEntity::getStatus, "REVOKED"));
            return keyView(keys.selectById(id));
        });
    }
    /** 长度与格式先校验，再做恒定时间摘要对比。 */
    @Override public ApplicationPrincipal authenticate(String bearer) {
        if (bearer == null || !bearer.matches("sop_[0-9a-f-]{36}_[A-Za-z0-9_-]{43}")) {
            throw IntegrationInputs.error(401, "INVALID_APPLICATION_KEY");
        }
        String keyId = bearer.substring(4, 40);
        ApplicationAccessKeyEntity key = keys.selectById(keyId);
        if (key == null || !MessageDigest.isEqual(key.getSecretHash().getBytes(StandardCharsets.US_ASCII),
                IntegrationInputs.digest(bearer).getBytes(StandardCharsets.US_ASCII))) {
            throw IntegrationInputs.error(401, "INVALID_APPLICATION_KEY");
        }
        ApplicationPrincipal principal = new ApplicationPrincipal(key.getApplicationId(), key.getId());
        current(principal);
        return principal;
    }
    /** 查询历史也必须具有当前授权，资源停用与任务历史终态分别处理。 */
    @Override public void authorize(ApplicationPrincipal actor, String permission, String environmentId) {
        IntegrationApplicationEntity application = current(actor);
        if (!permissions(application).contains(permission)) { throw IntegrationInputs.error(403, "APPLICATION_PERMISSION_DENIED"); }
        if (environmentId != null && grants.selectCount(Wrappers.<ApplicationEnvironmentGrantEntity>lambdaQuery()
                .eq(ApplicationEnvironmentGrantEntity::getApplicationId, actor.applicationId())
                .eq(ApplicationEnvironmentGrantEntity::getEnvironmentId, environmentId)) != 1) {
            throw IntegrationInputs.error(403, "APPLICATION_ENVIRONMENT_DENIED");
        }
    }
    /** 当前应用版本和密钥身份形成受理时的可审阅授权摘要。 */
    @Override public String authorizationRevision(ApplicationPrincipal actor) {
        IntegrationApplicationEntity application = current(actor);
        return IntegrationInputs.digest(application.getId() + "|" + application.getVersion() + "|" + actor.keyId());
    }
    /** 以服务器分钟窗口计数，不接受客户端额度。 */
    @Override public synchronized void rate(ApplicationPrincipal actor, boolean submission) {
        IntegrationApplicationEntity application = current(actor);
        int window = (int) (Instant.now().getEpochSecond() / 60);
        int[] rate = rates.computeIfAbsent(actor.applicationId(), key -> new int[] {window, 0, 0});
        if (rate[0] != window) { rate[0] = window; rate[1] = 0; rate[2] = 0; }
        int index = submission ? 1 : 2;
        int limit = submission ? application.getSubmitPerMinute() : application.getReadPerMinute();
        if (++rate[index] > limit) { throw IntegrationInputs.error(429, "APPLICATION_RATE_LIMITED"); }
    }
    /** 活动任务按真实连接释放计数。 */
    @Override public synchronized void acquire(ApplicationPrincipal actor) {
        IntegrationApplicationEntity application = current(actor);
        int count = active.getOrDefault(actor.applicationId(), 0);
        if (count >= application.getMaxActive()) { throw IntegrationInputs.error(429, "APPLICATION_ACTIVE_LIMIT"); }
        active.put(actor.applicationId(), count + 1);
    }
    /** 不因密钥已撤销而阻止资源归还。 */
    @Override public synchronized void release(ApplicationPrincipal actor) {
        int count = active.getOrDefault(actor.applicationId(), 0);
        if (count <= 1) { active.remove(actor.applicationId()); } else { active.put(actor.applicationId(), count - 1); }
    }
    /** 每次使用重新验证密钥期限和应用状态。 */
    private IntegrationApplicationEntity current(ApplicationPrincipal actor) {
        ApplicationAccessKeyEntity key = keys.selectById(actor.keyId());
        IntegrationApplicationEntity application = application(actor.applicationId());
        if (key == null || !actor.applicationId().equals(key.getApplicationId()) || !"ACTIVE".equals(key.getStatus())
                || !Instant.parse(key.getExpiresAt()).isAfter(Instant.now()) || !"ACTIVE".equals(application.getStatus())) {
            throw IntegrationInputs.error(401, "APPLICATION_AUTHORIZATION_REVOKED");
        }
        if (!Objects.equals(key.getApprovedGeneration(), generation())
                || !Objects.equals(application.getApprovedGeneration(), generation())) {
            throw IntegrationInputs.error(401, "RESTORED_AUTHORIZATION_REQUIRES_APPROVAL");
        }
        return application;
    }
    /** 读取应用，不向调用方暴露数据库异常。 */
    private IntegrationApplicationEntity application(String id) {
        IntegrationApplicationEntity value = applications.selectById(id);
        if (value == null) { throw IntegrationInputs.error(404, "APPLICATION_NOT_FOUND"); }
        return value;
    }
    /** 固定权限反序列化，存储损坏时拒绝授权。 */
    private Set<String> permissions(IntegrationApplicationEntity value) {
        try { return json.readValue(value.getPermissionsJson(), new TypeReference<Set<String>>() {}); }
        catch (Exception exception) { throw new IllegalStateException("APPLICATION_POLICY_INVALID"); }
    }
    /** 公开应用投影不包含任何访问密钥。 */
    private ApplicationView view(IntegrationApplicationEntity value) {
        Set<String> environments = grants.selectList(Wrappers.<ApplicationEnvironmentGrantEntity>lambdaQuery()
                .eq(ApplicationEnvironmentGrantEntity::getApplicationId, value.getId())).stream()
                .map(ApplicationEnvironmentGrantEntity::getEnvironmentId).collect(Collectors.toUnmodifiableSet());
        String status = Objects.equals(value.getApprovedGeneration(), generation()) ? value.getStatus() : "DISABLED";
        return new ApplicationView(value.getId(), value.getProjectId(), value.getCode(), value.getName(), status,
                value.getVersion(), permissions(value), environments, value.getSubmitPerMinute(), value.getReadPerMinute(), value.getMaxActive());
    }
    /** 密钥过期状态动态计算，数据库不因读取而写入。 */
    private AccessKeyView keyView(ApplicationAccessKeyEntity value) {
        String status = "ACTIVE".equals(value.getStatus()) && !Instant.parse(value.getExpiresAt()).isAfter(Instant.now()) ? "EXPIRED" : value.getStatus();
        if (!Objects.equals(value.getApprovedGeneration(), generation())) { status = "REVOKED"; }
        return new AccessKeyView(value.getId(), value.getApplicationId(), status, value.getExpiresAt(), value.getCreatedAt());
    }
    /** 只序列化受控类型。 */
    private String encode(Object value) {
        try { return json.writeValueAsString(value); } catch (Exception exception) { throw new IllegalStateException("APPLICATION_ENCODING_FAILED"); }
    }
    /** 管理员额度必须在固定上限内；省略字段保留原有值。 */
    private int quota(Integer requested, int existing, int maximum) {
        if (requested == null) { return existing; }
        if (requested < 1 || requested > maximum) { throw IntegrationInputs.error(400, "INVALID_APPLICATION_QUOTA"); }
        return requested;
    }
}
