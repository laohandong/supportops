package io.supportops.agent.dto;

import io.supportops.agent.memory.ConversationTurn;
import io.supportops.memory.vo.ProjectMemory;

import java.util.List;

/** 发送给模型的业务输入；历史问答和项目约束作为数据，不替代当前现场证据。 */
public record DiagnosisInput(
        String currentQuestion,
        List<ConversationTurn> previousConversation,
        List<ProjectMemory> confirmedProjectConstraints) {}
