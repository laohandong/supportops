package io.supportops.project.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.supportops.integration.service.support.EnterpriseIds;
import io.supportops.integration.service.support.IntegrationInputs;
import io.supportops.integration.service.support.ManagementCommands;
import io.supportops.project.dto.GrantCommand;
import io.supportops.project.dto.ResourceCommand;
import io.supportops.project.entity.EnvironmentUserGrantEntity;
import io.supportops.project.entity.ManagedSystemEntity;
import io.supportops.project.entity.ProjectEntity;
import io.supportops.project.entity.ProjectMemberEntity;
import io.supportops.project.entity.SystemEnvironmentEntity;
import io.supportops.project.mapper.EnvironmentUserGrantMapper;
import io.supportops.project.mapper.ManagedSystemMapper;
import io.supportops.project.mapper.ProjectMapper;
import io.supportops.project.mapper.ProjectMemberMapper;
import io.supportops.project.mapper.SystemEnvironmentMapper;
import io.supportops.project.service.ProjectService;
import io.supportops.project.vo.GrantView;
import io.supportops.project.vo.ResourceView;
import io.supportops.project.vo.TargetView;
import io.supportops.user.service.UserService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 维护明确的项目层级；查询权限以数据库当前关系为准。 */
@Service
public class ProjectServiceImpl implements ProjectService {
    private final ProjectMapper projects;
    private final ManagedSystemMapper systems;
    private final SystemEnvironmentMapper environments;
    private final ProjectMemberMapper members;
    private final EnvironmentUserGrantMapper grants;
    private final UserService users;
    private final ManagementCommands commands;
    private final boolean demoEnabled;
    private final boolean localDemo;
    private io.supportops.integration.service.support.RestoreGeneration restoreGeneration;
    /** 注入恢复代次；独立旧服务测试沿用初始代次零。 */
    @org.springframework.beans.factory.annotation.Autowired
    public void restoreGeneration(io.supportops.integration.service.support.RestoreGeneration value) { restoreGeneration = value; }
    /** 读取当前恢复代次，不改变已保存授权。 */
    private int generation() { return restoreGeneration == null ? 0 : restoreGeneration.current(); }

    /** 注入本模块 Mapper 与其他模块的公开业务接口。 */
    public ProjectServiceImpl(ProjectMapper projects, ManagedSystemMapper systems, SystemEnvironmentMapper environments,
            ProjectMemberMapper members, EnvironmentUserGrantMapper grants, UserService users, ManagementCommands commands,
            @Value("${supportops.enterprise.enabled:false}") boolean enterprise,
            @Value("${supportops.enterprise.demo-enabled:false}") boolean demoEnabled) {
        this.projects = projects;
        this.systems = systems;
        this.environments = environments;
        this.members = members;
        this.grants = grants;
        this.users = users;
        this.commands = commands;
        this.demoEnabled = !enterprise || demoEnabled;
        this.localDemo = !enterprise;
    }

    /** 管理资源列表包含停用与归档记录，用于审计和维护。 */
    @Override
    public List<ResourceView> resources() {
        ManagementCommands.administrator();
        List<ResourceView> result = new ArrayList<>();
        projects.selectList(Wrappers.<ProjectEntity>lambdaQuery().orderByAsc(ProjectEntity::getCode))
                .forEach(value -> result.add(view(value)));
        systems.selectList(Wrappers.<ManagedSystemEntity>lambdaQuery().orderByAsc(ManagedSystemEntity::getCode))
                .forEach(value -> result.add(view(value)));
        environments.selectList(Wrappers.<SystemEnvironmentEntity>lambdaQuery().orderByAsc(SystemEnvironmentEntity::getCode))
                .forEach(value -> result.add(view(value)));
        return List.copyOf(result);
    }

    /** 资源变更与回执同事务，唯一编码冲突返回稳定业务错误。 */
    @Override
    public ResourceView save(String kind, ResourceCommand input) {
        try {
            return commands.execute(input.requestId(), "RESOURCE_" + kind, input, ResourceView.class, () -> {
                String code = IntegrationInputs.code(input.code());
                String name = IntegrationInputs.text(input.name(), 128);
                String status = input.status() == null ? "ACTIVE" : input.status();
                if (!List.of("ACTIVE", "DISABLED", "ARCHIVED").contains(status)) {
                    throw IntegrationInputs.error(400, "INVALID_RESOURCE_STATUS");
                }
                boolean creation = input.id() == null;
                String id = creation ? UUID.randomUUID().toString() : IntegrationInputs.uuid(input.id());
                if (!creation && (input.expectedVersion() == null || input.expectedVersion() < 1)) {
                    throw IntegrationInputs.error(400, "EXPECTED_VERSION_REQUIRED");
                }
                return switch (kind) {
                    case "PROJECT" -> saveProject(input, id, creation, code, name, status);
                    case "SYSTEM" -> saveSystem(input, id, creation, code, name, status);
                    case "ENVIRONMENT" -> saveEnvironment(input, id, creation, code, name, status);
                    default -> throw IntegrationInputs.error(400, "INVALID_RESOURCE_KIND");
                };
            });
        } catch (DuplicateKeyException exception) {
            throw IntegrationInputs.error(409, "RESOURCE_CODE_EXISTS");
        }
    }

    /** 项目归档后禁止恢复；旧版本条件更新防止覆盖其他管理员的变更。 */
    private ResourceView saveProject(ResourceCommand input, String id, boolean creation, String code, String name, String status) {
        if (creation) {
            ProjectEntity value = new ProjectEntity();
            value.setId(id);
            value.setCode(code);
            value.setName(name);
            value.setStatus(status);
            value.setVersion(1);
            value.setCreatedAt(Instant.now().toString());
            projects.insert(value);
        } else {
            requireMutable(projects.selectById(id));
            updated(projects.update(Wrappers.<ProjectEntity>lambdaUpdate().eq(ProjectEntity::getId, id)
                    .eq(ProjectEntity::getVersion, input.expectedVersion()).ne(ProjectEntity::getStatus, "ARCHIVED")
                    .set(ProjectEntity::getName, name).set(ProjectEntity::getStatus, status)
                    .set(ProjectEntity::getVersion, input.expectedVersion() + 1)));
        }
        return view(projects.selectById(id));
    }

    /** 系统的项目归属和协议编码创建后固定，避免历史目标被重新解释。 */
    private ResourceView saveSystem(ResourceCommand input, String id, boolean creation, String code, String name, String status) {
        if (creation) {
            String parent = IntegrationInputs.uuid(input.parentId());
            requireMutable(projects.selectById(parent));
            ManagedSystemEntity value = new ManagedSystemEntity();
            value.setId(id);
            value.setProjectId(parent);
            value.setCode(code);
            value.setName(name);
            value.setProductKey(IntegrationInputs.code(input.productKey()));
            value.setStatus(status);
            value.setVersion(1);
            systems.insert(value);
        } else {
            ManagedSystemEntity previous = systems.selectById(id);
            if (previous == null || "ARCHIVED".equals(previous.getStatus())) {
                throw IntegrationInputs.error(409, "RESOURCE_UNAVAILABLE");
            }
            updated(systems.update(Wrappers.<ManagedSystemEntity>lambdaUpdate().eq(ManagedSystemEntity::getId, id)
                    .eq(ManagedSystemEntity::getVersion, input.expectedVersion()).ne(ManagedSystemEntity::getStatus, "ARCHIVED")
                    .set(ManagedSystemEntity::getName, name).set(ManagedSystemEntity::getStatus, status)
                    .set(ManagedSystemEntity::getVersion, input.expectedVersion() + 1)));
        }
        return view(systems.selectById(id));
    }

    /** 预期版本更新使后续诊断重新核对现场身份，不改写已保存的目标快照。 */
    private ResourceView saveEnvironment(ResourceCommand input, String id, boolean creation, String code, String name, String status) {
        String version = IntegrationInputs.text(input.productVersion(), 30);
        if (creation) {
            String parent = IntegrationInputs.uuid(input.parentId());
            ManagedSystemEntity system = systems.selectById(parent);
            if (system == null || "ARCHIVED".equals(system.getStatus())) {
                throw IntegrationInputs.error(409, "RESOURCE_UNAVAILABLE");
            }
            SystemEnvironmentEntity value = new SystemEnvironmentEntity();
            value.setId(id);
            value.setSystemId(parent);
            value.setCode(code);
            value.setName(name);
            value.setProductVersion(version);
            value.setStatus(status);
            value.setVersion(1);
            environments.insert(value);
        } else {
            SystemEnvironmentEntity previous = environments.selectById(id);
            if (previous == null || "ARCHIVED".equals(previous.getStatus())) {
                throw IntegrationInputs.error(409, "RESOURCE_UNAVAILABLE");
            }
            updated(environments.update(Wrappers.<SystemEnvironmentEntity>lambdaUpdate().eq(SystemEnvironmentEntity::getId, id)
                    .eq(SystemEnvironmentEntity::getVersion, input.expectedVersion()).ne(SystemEnvironmentEntity::getStatus, "ARCHIVED")
                    .set(SystemEnvironmentEntity::getName, name).set(SystemEnvironmentEntity::getStatus, status)
                    .set(SystemEnvironmentEntity::getProductVersion, version)
                    .set(SystemEnvironmentEntity::getVersion, input.expectedVersion() + 1)));
        }
        return view(environments.selectById(id));
    }

    /** 显式授权不能隐式创建项目成员，撤销成员会立即使全部环境授权失效。 */
    @Override
    public GrantView grant(String kind, GrantCommand input) {
        return commands.execute(input.requestId(), "GRANT_" + kind, input, GrantView.class, () -> {
            String resource = IntegrationInputs.uuid(input.resourceId());
            String user = IntegrationInputs.uuid(input.userId());
            if (users.find(user) == null) {
                throw IntegrationInputs.error(404, "USER_NOT_FOUND");
            }
            if ("PROJECT".equals(kind)) {
                requireMutable(projects.selectById(resource));
                ProjectMemberEntity existing = members.selectOne(Wrappers.<ProjectMemberEntity>lambdaQuery()
                        .eq(ProjectMemberEntity::getProjectId, resource).eq(ProjectMemberEntity::getUserId, user));
                if (input.granted() && existing == null) {
                    ProjectMemberEntity member = new ProjectMemberEntity();
                    member.setId(UUID.randomUUID().toString());
                    member.setProjectId(resource);
                    member.setUserId(user);
                    member.setVersion(1);
                    members.insert(member);
                } else if (!input.granted() && existing != null) {
                    members.deleteById(existing.getId());
                }
            } else if ("ENVIRONMENT".equals(kind)) {
                SystemEnvironmentEntity environment = environments.selectById(resource);
                ManagedSystemEntity system = environment == null ? null : systems.selectById(environment.getSystemId());
                if (system == null) { throw IntegrationInputs.error(404, "TARGET_NOT_AVAILABLE"); }
                if (input.granted()) { resolve(resource, null, true); }
                if (input.granted() && !member(system.getProjectId(), user)) {
                    throw IntegrationInputs.error(409, "PROJECT_MEMBERSHIP_REQUIRED");
                }
                EnvironmentUserGrantEntity existing = grants.selectOne(Wrappers.<EnvironmentUserGrantEntity>lambdaQuery()
                        .eq(EnvironmentUserGrantEntity::getEnvironmentId, resource).eq(EnvironmentUserGrantEntity::getUserId, user));
                if (input.granted() && existing == null) {
                    EnvironmentUserGrantEntity grant = new EnvironmentUserGrantEntity();
                    grant.setId(UUID.randomUUID().toString());
                    grant.setEnvironmentId(resource);
                    grant.setUserId(user);
                    grant.setVersion(1);
                    grants.insert(grant);
                } else if (!input.granted() && existing != null) {
                    grants.deleteById(existing.getId());
                }
            } else {
                throw IntegrationInputs.error(400, "INVALID_GRANT_KIND");
            }
            return new GrantView(resource, user, input.granted());
        });
    }

    /** 只展示当前有完整权限且已配置连接的目标。 */
    @Override
    public List<TargetView> targets(String userId, boolean administrator) {
        List<TargetView> result = new ArrayList<>();
        for (SystemEnvironmentEntity environment : environments.selectList(Wrappers.<SystemEnvironmentEntity>lambdaQuery()
                .eq(SystemEnvironmentEntity::getStatus, "ACTIVE").isNotNull(SystemEnvironmentEntity::getCurrentConnectorRevisionId))) {
            try {
                TargetView target = resolve(environment.getId(), userId, administrator);
                if (target.connectorRevisionId() != null) { result.add(target); }
            } catch (org.springframework.web.server.ResponseStatusException exception) {
                if (exception.getStatusCode().value() != 404) {
                    throw exception;
                }
            }
        }
        return List.copyOf(result);
    }

    /** 服务内部校验资料归属，授权由资料管理接口负责。 */
    @Override
    public void validateSystem(String systemId) {
        ManagedSystemEntity system = systems.selectById(IntegrationInputs.uuid(systemId));
        ProjectEntity project = system == null ? null : projects.selectById(system.getProjectId());
        if (system == null || project == null || !"ACTIVE".equals(system.getStatus()) || !"ACTIVE".equals(project.getStatus())) {
            throw IntegrationInputs.error(404, "SYSTEM_NOT_AVAILABLE");
        }
    }

    /** 所有父级必须可用；普通用户同时满足项目成员及环境授权。 */
    @Override
    public TargetView resolve(String environmentId, String userId, boolean administrator) {
        String id = IntegrationInputs.uuid(environmentId);
        SystemEnvironmentEntity environment = environments.selectById(id);
        ManagedSystemEntity system = environment == null ? null : systems.selectById(environment.getSystemId());
        ProjectEntity project = system == null ? null : projects.selectById(system.getProjectId());
        boolean active = environment != null && system != null && project != null
                && "ACTIVE".equals(environment.getStatus()) && "ACTIVE".equals(system.getStatus())
                && "ACTIVE".equals(project.getStatus());
        boolean demoAllowed = demoEnabled || !EnterpriseIds.ENVIRONMENT.equals(id);
        boolean allowed = historyAllowed(id, userId, administrator);
        if (!active || !allowed || !demoAllowed) {
            throw IntegrationInputs.error(404, "TARGET_NOT_AVAILABLE");
        }
        return new TargetView(project.getId(), project.getName(), system.getId(), system.getCode(), system.getName(),
                environment.getId(), environment.getCode(), environment.getName(), system.getProductKey(),
                environment.getProductVersion(), java.util.Objects.equals(environment.getApprovedGeneration(), generation())
                        ? environment.getCurrentConnectorRevisionId() : null);
    }

    /** 摘要包含关系主键，撤销再授予不会伪装成同一份历史授权。 */
    @Override
    public String authorizationRevision(TargetView target, String userId, boolean administrator) {
        ProjectEntity project = projects.selectById(target.projectId());
        ManagedSystemEntity system = systems.selectById(target.systemId());
        SystemEnvironmentEntity environment = environments.selectById(target.environmentId());
        ProjectMemberEntity member = userId == null ? null : members.selectOne(Wrappers.<ProjectMemberEntity>lambdaQuery()
                .eq(ProjectMemberEntity::getProjectId, target.projectId()).eq(ProjectMemberEntity::getUserId, userId));
        EnvironmentUserGrantEntity grant = userId == null ? null : grants.selectOne(Wrappers.<EnvironmentUserGrantEntity>lambdaQuery()
                .eq(EnvironmentUserGrantEntity::getEnvironmentId, target.environmentId()).eq(EnvironmentUserGrantEntity::getUserId, userId));
        return IntegrationInputs.digest(project.getVersion() + "|" + system.getVersion() + "|" + environment.getVersion()
                + "|" + administrator + "|" + (member == null ? "absent" : member.getId() + ":" + member.getVersion())
                + "|" + (grant == null ? "absent" : grant.getId() + ":" + grant.getVersion()));
    }

    /** 调用方在环境维护锁内激活经过测试的修订。 */
    @Override
    @Transactional
    public void activate(String environmentId, String revisionId, int expectedVersion) {
        ManagementCommands.administrator();
        updated(environments.update(Wrappers.<SystemEnvironmentEntity>lambdaUpdate()
                .eq(SystemEnvironmentEntity::getId, IntegrationInputs.uuid(environmentId))
                .eq(SystemEnvironmentEntity::getVersion, expectedVersion).eq(SystemEnvironmentEntity::getStatus, "ACTIVE")
                .set(SystemEnvironmentEntity::getCurrentConnectorRevisionId, IntegrationInputs.uuid(revisionId))
                .set(SystemEnvironmentEntity::getApprovedGeneration, generation())
                .set(SystemEnvironmentEntity::getVersion, expectedVersion + 1)));
    }

    /** 判断项目成员是否仍有效。 */
    @Override public void validateScope(String projectId, String systemId, String environmentId) {
        ProjectEntity project = projects.selectById(IntegrationInputs.uuid(projectId));
        ManagedSystemEntity system = systemId == null ? null : systems.selectById(IntegrationInputs.uuid(systemId));
        SystemEnvironmentEntity environment = environmentId == null ? null : environments.selectById(IntegrationInputs.uuid(environmentId));
        if (project == null || !"ACTIVE".equals(project.getStatus())
                || (systemId != null && (system == null || !projectId.equals(system.getProjectId()) || !"ACTIVE".equals(system.getStatus())))
                || (environmentId != null && (environment == null || systemId == null
                        || !systemId.equals(environment.getSystemId()) || !"ACTIVE".equals(environment.getStatus())))) {
            throw IntegrationInputs.error(400, "MEMORY_SCOPE_MISMATCH");
        }
    }
    /** 判断项目成员是否仍有效。 */
    private boolean member(String project, String user) {
        return members.selectCount(Wrappers.<ProjectMemberEntity>lambdaQuery()
                .eq(ProjectMemberEntity::getProjectId, project).eq(ProjectMemberEntity::getUserId, user)) > 0;
    }
    /** 本地示例保留新增用户兼容；企业环境包含示例均需实际授权。 */
    @Override public boolean historyAllowed(String environmentId, String userId, boolean administrator) {
        if (administrator) { return true; }
        if (userId == null) { return false; }
        if (localDemo && EnterpriseIds.ENVIRONMENT.equals(environmentId)) { return true; }
        SystemEnvironmentEntity environment = environments.selectById(environmentId);
        ManagedSystemEntity system = environment == null ? null : systems.selectById(environment.getSystemId());
        return system != null && member(system.getProjectId(), userId)
                && grants.selectCount(Wrappers.<EnvironmentUserGrantEntity>lambdaQuery()
                        .eq(EnvironmentUserGrantEntity::getEnvironmentId, environmentId)
                        .eq(EnvironmentUserGrantEntity::getUserId, userId)) > 0;
    }
    /** 归档项目不能写入子资源或重新启用。 */
    private void requireMutable(ProjectEntity project) {
        if (project == null || "ARCHIVED".equals(project.getStatus())) {
            throw IntegrationInputs.error(409, "RESOURCE_UNAVAILABLE");
        }
    }
    /** 检查条件更新结果，不把版本冲突包装为成功。 */
    private void updated(int count) {
        if (count != 1) {
            throw IntegrationInputs.error(409, "RESOURCE_VERSION_CONFLICT");
        }
    }
    /** 投影公开项目信息。 */
    private ResourceView view(ProjectEntity value) {
        return new ResourceView(value.getId(), "PROJECT", null, value.getCode(), value.getName(), value.getStatus(),
                value.getVersion(), null, null, null);
    }
    /** 投影公开系统信息。 */
    private ResourceView view(ManagedSystemEntity value) {
        return new ResourceView(value.getId(), "SYSTEM", value.getProjectId(), value.getCode(), value.getName(), value.getStatus(),
                value.getVersion(), value.getProductKey(), null, null);
    }
    /** 投影公开环境信息。 */
    private ResourceView view(SystemEnvironmentEntity value) {
        return new ResourceView(value.getId(), "ENVIRONMENT", value.getSystemId(), value.getCode(), value.getName(), value.getStatus(),
                value.getVersion(), null, value.getProductVersion(), value.getCurrentConnectorRevisionId());
    }
}
