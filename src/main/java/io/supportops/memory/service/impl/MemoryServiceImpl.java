package io.supportops.memory.service.impl;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import io.supportops.memory.constant.MemoryLimits;
import io.supportops.memory.entity.ProjectMemoryEntity;
import io.supportops.memory.mapper.ProjectMemoryMapper;
import io.supportops.memory.service.MemoryService;
import io.supportops.memory.vo.ProjectMemory;
import io.supportops.integration.service.support.EnterpriseIds;
import io.supportops.integration.service.support.IntegrationInputs;
import io.supportops.project.service.ProjectService;
import io.supportops.project.vo.ResourceView;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 使用 MyBatis-Plus 保存已确认的项目记忆，保持原有单实例数量限制。 */
@Service
public class MemoryServiceImpl implements MemoryService {
    private final ProjectMemoryMapper memories;
    private final ProjectService projects;

    /** 注入项目记忆持久化入口。 */
    public MemoryServiceImpl(ProjectMemoryMapper memories) {
        this(memories, null);
    }
    /** 企业保存约束时验证完整归属，旧持久化夹具保留原构造器。 */
    @Autowired public MemoryServiceImpl(ProjectMemoryMapper memories, ProjectService projects) {
        this.memories = memories;
        this.projects = projects;
    }

    /** 按时间读取实体并转换为不含持久化细节的响应。 */
    @Override
    public List<ProjectMemory> list() {
        return scoped(EnterpriseIds.PROJECT, EnterpriseIds.SYSTEM, EnterpriseIds.ENVIRONMENT);
    }

    /** 项目级约束和匹配系统、环境的约束同时适用，其他项目资料不得进入上下文。 */
    @Override public List<ProjectMemory> scoped(String projectId, String systemId, String environmentId) {
        return memories
                .selectList(
                        Wrappers.<ProjectMemoryEntity>lambdaQuery()
                                .eq(ProjectMemoryEntity::getProjectId, projectId)
                                .and(scope -> scope.isNull(ProjectMemoryEntity::getSystemId).or().eq(ProjectMemoryEntity::getSystemId, systemId))
                                .and(scope -> scope.isNull(ProjectMemoryEntity::getEnvironmentId).or().eq(ProjectMemoryEntity::getEnvironmentId, environmentId))
                                .orderByAsc(ProjectMemoryEntity::getCreatedAt))
                .stream()
                .map(this::toView)
                .toList();
    }

    /** 数量校验与插入共用单实例互斥锁，避免并发写入绕过上限。 */
    @Override
    public synchronized ProjectMemory add(String content, boolean confirmed) {
        return addScoped(content, confirmed, EnterpriseIds.PROJECT, null, null);
    }
    /** 校验范围关系后保存，数量限制按项目独立计算。 */
    @Override public synchronized ProjectMemory addScoped(String content, boolean confirmed, String projectId, String systemId, String environmentId) {
        IntegrationInputs.uuid(projectId);
        if (systemId != null) { IntegrationInputs.uuid(systemId); }
        if (environmentId != null) { IntegrationInputs.uuid(environmentId); }
        if (projects != null) {
            projects.validateScope(projectId, systemId, environmentId);
        }
        if (!confirmed) {
            throw new ResponseStatusException(BAD_REQUEST, "EXPLICIT_CONFIRMATION_REQUIRED");
        }
        if (content == null
                || content.isBlank()
                || content.length() > MemoryLimits.CONTENT_LENGTH) {
            throw new ResponseStatusException(BAD_REQUEST, "INVALID_MEMORY");
        }
        if (memories.selectCount(Wrappers.<ProjectMemoryEntity>lambdaQuery().eq(ProjectMemoryEntity::getProjectId, projectId)) >= MemoryLimits.MAX_ENTRIES) {
            throw new ResponseStatusException(CONFLICT, "MEMORY_LIMIT_REACHED");
        }
        ProjectMemoryEntity record =
                new ProjectMemoryEntity(
                        UUID.randomUUID().toString(), content.strip(), Instant.now().toString());
        record.setProjectId(projectId); record.setSystemId(systemId); record.setEnvironmentId(environmentId);
        if (memories.insert(record) != 1) {
            throw new IllegalStateException("MEMORY_PERSISTENCE_FAILED");
        }
        return toView(record);
    }

    /** 通过主键删除记录，不存在时保持原有 404 行为。 */
    @Override
    public void delete(String id) {
        if (memories.deleteById(id) != 1) {
            throw new ResponseStatusException(NOT_FOUND, "MEMORY_NOT_FOUND");
        }
    }

    /** 映射为公开的项目记忆结构，保持原有字段名称。 */
    private ProjectMemory toView(ProjectMemoryEntity record) {
        return new ProjectMemory(record.getId(), record.getContent(), record.getCreatedAt(),
                record.getProjectId(), record.getSystemId(), record.getEnvironmentId());
    }
}
