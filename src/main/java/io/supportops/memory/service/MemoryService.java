package io.supportops.memory.service;

import io.supportops.memory.vo.ProjectMemory;

import java.util.List;

/** 人员确认的项目记忆服务，供接口与诊断上下文共同使用。 */
public interface MemoryService {
    /** 按确认时间正序读取当前项目约束。 */
    List<ProjectMemory> list();

    /** 仅接受明确确认且符合内容和数量限制的约束。 */
    ProjectMemory add(String content, boolean confirmed);

    /** 删除当前约束，不改写历史诊断中的证据快照。 */
    void delete(String id);
}
