package io.supportops.agent.service;

import io.supportops.agent.vo.DiagnosisEvent;
import io.supportops.agent.vo.DiagnosisRun;

import java.util.List;
import java.util.function.Supplier;

/** 管理持久化诊断任务与单个可变示例环境的执行准入。 */
public interface RunService {
    /** 校验并受理诊断；返回快照不代表模型已经执行完成。 */
    DiagnosisRun start(String question, String sessionId, boolean lexicalOnly);

    /** 当前有任务尚未释放执行资源时拒绝操作。 */
    void requireIdle();

    /** 在与诊断准入相同的互斥边界内执行人工操作，避免检查后再修改的竞争。 */
    <T> T whenIdle(Supplier<T> operation);

    /** 请求取消任务；重复取消已结束任务时保留原终态。 */
    DiagnosisRun cancel(String id);

    /** 逻辑归档指定任务所属的整个对话，重复请求不改变归档时间或计量。 */
    void archive(String id);

    /** 按创建时间倒序读取最近一百条任务。 */
    List<DiagnosisRun> list();

    /** 读取任务，不存在时返回明确的未找到错误。 */
    DiagnosisRun get(String id);

    /** 按递增游标读取该任务中编号大于 after 的事件。 */
    List<DiagnosisEvent> events(String id, long after);
}
