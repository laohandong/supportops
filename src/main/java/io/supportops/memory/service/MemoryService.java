package io.supportops.memory.service;

import io.supportops.memory.vo.ProjectMemory;

import java.util.List;

/** 人员确认的项目记忆服务，供接口与诊断上下文共同使用。 */
public interface MemoryService {
    /** 按确认时间正序读取当前项目约束。 */
    List<ProjectMemory> list();
    /** 获取与明确目标相匹配的已确认约束；旧实现不能扩展至企业项目。 */
    default List<ProjectMemory> scoped(String projectId, String systemId, String environmentId) {
        if (!io.supportops.integration.service.support.EnterpriseIds.PROJECT.equals(projectId)) { return List.of(); }
        return list();
    }

    /** 仅接受明确确认且符合内容和数量限制的约束。 */
    ProjectMemory add(String content, boolean confirmed);
    /** 在明确项目、可选系统和环境范围保存已确认约束。 */
    default ProjectMemory addScoped(String content, boolean confirmed, String projectId, String systemId, String environmentId) {
        if (!io.supportops.integration.service.support.EnterpriseIds.PROJECT.equals(projectId)) { throw new IllegalArgumentException("PROJECT_SCOPE_REQUIRED"); }
        return add(content, confirmed);
    }

    /** 删除当前约束，不改写历史诊断中的证据快照。 */
    void delete(String id);
}
