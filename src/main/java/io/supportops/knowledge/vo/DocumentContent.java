package io.supportops.knowledge.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** 文档元数据和原始分片详情。 */
@Schema(name = "DocumentContent", description = "文档元数据和原始分片详情。")
public record DocumentContent(
        @Schema(description = "文档元数据。", schemaResolution = Schema.SchemaResolution.ALL_OF)
                KnowledgeDocument document,
        @Schema(description = "原始分片列表。") List<DocumentChunk> chunks) {}
