package io.supportops.agent.memory;

/** 已经完成的一轮历史问答，用于构建受长度限制的会话上下文。 */
public record ConversationTurn(String question, String answer) {}
