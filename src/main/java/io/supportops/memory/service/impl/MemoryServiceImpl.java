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

import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 使用 MyBatis-Plus 保存已确认的项目记忆，保持原有单实例数量限制。 */
@Service
public class MemoryServiceImpl implements MemoryService {
    private final ProjectMemoryMapper memories;

    /** 注入项目记忆持久化入口。 */
    public MemoryServiceImpl(ProjectMemoryMapper memories) {
        this.memories = memories;
    }

    /** 按时间读取实体并转换为不含持久化细节的响应。 */
    @Override
    public List<ProjectMemory> list() {
        return memories
                .selectList(
                        Wrappers.<ProjectMemoryEntity>lambdaQuery()
                                .orderByAsc(ProjectMemoryEntity::getCreatedAt))
                .stream()
                .map(this::toView)
                .toList();
    }

    /** 数量校验与插入共用单实例互斥锁，避免并发写入绕过上限。 */
    @Override
    public synchronized ProjectMemory add(String content, boolean confirmed) {
        if (!confirmed) {
            throw new ResponseStatusException(BAD_REQUEST, "EXPLICIT_CONFIRMATION_REQUIRED");
        }
        if (content == null
                || content.isBlank()
                || content.length() > MemoryLimits.CONTENT_LENGTH) {
            throw new ResponseStatusException(BAD_REQUEST, "INVALID_MEMORY");
        }
        if (memories.selectCount(null) >= MemoryLimits.MAX_ENTRIES) {
            throw new ResponseStatusException(CONFLICT, "MEMORY_LIMIT_REACHED");
        }
        ProjectMemoryEntity record =
                new ProjectMemoryEntity(
                        UUID.randomUUID().toString(), content.strip(), Instant.now().toString());
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
        return new ProjectMemory(record.getId(), record.getContent(), record.getCreatedAt());
    }
}
