package io.supportops.knowledge.dto;

/** 经过数据库版本过滤的检索投影；向量正文允许为 null。 */
public record StoredPassage(
        String id,
        String documentId,
        String title,
        String version,
        String location,
        String content,
        String embedding,
        String embeddingKey) {}
