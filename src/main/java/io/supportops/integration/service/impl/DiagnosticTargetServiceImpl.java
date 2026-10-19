package io.supportops.integration.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.supportops.config.ProviderRegistry;
import io.supportops.integration.entity.RunTargetSnapshotEntity;
import io.supportops.integration.mapper.RunTargetSnapshotMapper;
import io.supportops.integration.service.ApplicationService;
import io.supportops.integration.service.ConnectorService;
import io.supportops.integration.service.DiagnosticTargetService;
import io.supportops.integration.service.DataPolicyService;
import io.supportops.integration.vo.DataPolicyView;
import io.supportops.integration.service.support.IntegrationInputs;
import io.supportops.integration.vo.ApplicationPrincipal;
import io.supportops.integration.vo.DiagnosticScope;
import io.supportops.project.service.ProjectService;
import io.supportops.project.vo.TargetView;
import io.supportops.user.service.UserService;
import io.supportops.user.vo.UserView;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

/** 只允许服务器决定目标，后续授权扩大不会扩大已受理任务的数据范围。 */
@Service
public class DiagnosticTargetServiceImpl implements DiagnosticTargetService {
    private final ProjectService projects;
    private final ApplicationService applications;
    private final ConnectorService connectors;
    private final UserService users;
    private final RunTargetSnapshotMapper snapshots;
    private final ObjectMapper json;
    private final DataPolicyService policies;
    private final ProviderRegistry providers;
    private final String deploymentId;
    /** 已捕获记录及推定示例记录均按当前环境权限读取。 */
    @Override public boolean historyAllowed(String runId, String userId, boolean administrator) {
        DiagnosticScope scope = snapshot(runId);
        UserView current = userId == null ? null : users.find(userId);
        return projects.historyAllowed(scope == null ? io.supportops.integration.service.support.EnterpriseIds.ENVIRONMENT
                : scope.target().environmentId(), userId, administrator || (current != null && current.isAdmin()));
    }
    /** 注入跨模块业务接口及本模块快照存储。 */
    public DiagnosticTargetServiceImpl(ProjectService projects, ApplicationService applications, ConnectorService connectors,
            UserService users, RunTargetSnapshotMapper snapshots, ObjectMapper json, DataPolicyService policies,
            ProviderRegistry providers, @Value("${supportops.enterprise.deployment-id:}") String deploymentId) {
        this.projects = projects; this.applications = applications; this.connectors = connectors;
        this.users = users; this.snapshots = snapshots; this.json = json;
        this.policies = policies;
        this.providers = providers;
        this.deploymentId = deploymentId.isBlank() ? null : IntegrationInputs.uuid(deploymentId);
    }
    /** 人员身份只从已认证链传入，普通用户同时检查项目和环境授权。 */
    @Override public DiagnosticScope user(String environmentId, UserView actor) {
        if (actor == null) { throw IntegrationInputs.error(401, "AUTHENTICATION_REQUIRED"); }
        TargetView target = projects.resolve(environmentId, actor.id(), actor.isAdmin());
        return scope(target, "USER", actor.id(), null, actor.isAdmin());
    }
    /** 应用权限与人员角色完全独立。 */
    @Override public DiagnosticScope application(String environmentId, ApplicationPrincipal actor) {
        applications.authorize(actor, "DIAGNOSE", environmentId);
        TargetView target = projects.resolve(environmentId, null, true);
        return scope(target, "APPLICATION", actor.applicationId(), actor.keyId(), false);
    }
    /** 捕获连接能力外发策略，未启用连接拒绝受理。 */
    private DiagnosticScope scope(TargetView target, String type, String actor, String key, boolean administrator) {
        if (target.connectorRevisionId() == null) { throw IntegrationInputs.error(409, "CONNECTOR_NOT_ENABLED"); }
        DataPolicyView policy = policies.policy(target.systemId());
        return new DiagnosticScope(target, type, actor, key, administrator,
                Set.copyOf(connectors.policy(target.connectorRevisionId()).modelData()),
                policy.chatAllowed(), policy.embeddingAllowed(), policy.version(), deploymentId,
                "APPLICATION".equals(type) ? applications.authorizationRevision(new ApplicationPrincipal(actor, key))
                        : projects.authorizationRevision(target, actor, administrator),
                providers.executionSnapshot(true, false).description());
    }
    /** 每个工具及每次外发均复核当前权限，保持受理策略和当前策略交集。 */
    @Override public void check(DiagnosticScope scope, String capability) {
        TargetView current;
        try {
            if ("APPLICATION".equals(scope.actorType())) {
                applications.authorize(new ApplicationPrincipal(scope.actorId(), scope.keyId()), "DIAGNOSE", scope.target().environmentId());
                current = projects.resolve(scope.target().environmentId(), null, true);
            } else {
                UserView user = users.find(scope.actorId());
                if (user == null) { throw IntegrationInputs.error(403, "AUTHORIZATION_REVOKED"); }
                current = projects.resolve(scope.target().environmentId(), user.id(), scope.administratorAtAdmission() && user.isAdmin());
            }
        } catch (org.springframework.web.server.ResponseStatusException exception) {
            throw IntegrationInputs.error(403, "AUTHORIZATION_REVOKED");
        }
        if (!current.productVersion().equals(scope.target().productVersion())) { throw IntegrationInputs.error(409, "TARGET_IDENTITY_MISMATCH"); }
        if (current.connectorRevisionId() == null) { throw IntegrationInputs.error(403, "TARGET_DISABLED"); }
        if ("DOCUMENT_CHAT".equals(capability)) {
            if (!scope.documentChatAllowed()) { throw IntegrationInputs.error(403, "DATA_EXTERNALIZATION_DENIED"); }
            policies.requireChat(scope.target().systemId());
            return;
        }
        if ("DOCUMENT_EMBEDDING".equals(capability)) {
            if (!scope.documentEmbeddingAllowed() || !policies.policy(scope.target().systemId()).embeddingAllowed()) {
                throw IntegrationInputs.error(403, "DATA_EXTERNALIZATION_DENIED");
            }
            return;
        }
        if (capability != null && (!scope.modelData().contains(capability)
                || !connectors.policy(current.connectorRevisionId()).modelData().contains(capability))) {
            throw IntegrationInputs.error(403, "DATA_EXTERNALIZATION_DENIED");
        }
    }
    /** 与受理同一事务写入不可变快照，不在执行过程中覆盖。 */
    @Override public void save(String runId, DiagnosticScope scope) {
        try {
            RunTargetSnapshotEntity value = new RunTargetSnapshotEntity(); value.setRunId(runId);
            value.setEnvironmentId(scope.target().environmentId()); value.setSnapshotKind("CAPTURED");
            value.setSnapshotJson(json.writeValueAsString(scope)); snapshots.insert(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("TARGET_SNAPSHOT_ENCODING_FAILED");
        }
    }
    /** 未捕获真实身份的旧记录维持推定标识，不能伪造完整快照。 */
    @Override public DiagnosticScope snapshot(String runId) {
        RunTargetSnapshotEntity value = snapshots.selectById(runId);
        if (value == null || !"CAPTURED".equals(value.getSnapshotKind())) { return null; }
        try { return json.readValue(value.getSnapshotJson(), DiagnosticScope.class); }
        catch (Exception exception) { throw new IllegalStateException("TARGET_SNAPSHOT_INVALID"); }
    }
}
