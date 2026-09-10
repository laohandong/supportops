package io.supportops.knowledge.service;

/** 事务提交后提示调度器扫描；消息丢失仍由定时扫描补捞。 */
public record KnowledgeSubmittedEvent(String batchId) {}
