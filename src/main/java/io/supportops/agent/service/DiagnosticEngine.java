package io.supportops.agent.service;

import io.supportops.agent.memory.ConversationTurn;
import io.supportops.memory.vo.ProjectMemory;

import java.util.List;
import java.util.function.BiConsumer;

/** 诊断执行边界，实现必须响应中断并通过回调报告可持久化事件。 */
public interface DiagnosticEngine {
    /** 单次诊断的不可变输入，包含当前问题、已完成问答和人员确认的约束。 */
    record Context(
            String question,
            List<ConversationTurn> history,
            List<ProjectMemory> memories,
            boolean lexicalOnly) {}

    /** 模型执行汇总，包含回答、报告用量及是否触及推理轮数限制。 */
    record Result(String answer, long inputTokens, long outputTokens, boolean stepLimitReached) {}

    /** 检查本地配置是否满足执行要求，不发起远程模型请求。 */
    boolean configured();

    /** 返回当前选择的对话模型名称。 */
    String modelName();

    /** 执行一次诊断，通过 events 回调报告工具、知识和用量；调用方负责生命周期。 */
    Result execute(Context context, BiConsumer<String, Object> events);
}
