package io.supportops.knowledge.dto;

import io.supportops.knowledge.constant.KnowledgeLimits;
import io.swagger.v3.oas.annotations.media.Schema;

/** 上传文档的 multipart/form-data 表单，三个字段均为必填。 */
@Schema(name = "UploadDocumentForm", description = "上传文档的 multipart/form-data 表单，三个字段均为必填。")
public record UploadDocumentForm(
        @Schema(
                        description = "UTF-8 MD、PDF、XLS 或 XLSX 文件，最大 20 MiB。",
                        type = "string",
                        format = "binary",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                String file,
        @Schema(
                        description = "文档标题，不能为空白，最多 180 字符。",
                        minLength = 1,
                        maxLength = 180,
                        example = "OrderBridge 2.0 升级说明",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                String title,
        @Schema(
                        description = "适用版本，最多 30 字符；* 表示通用资料。",
                        maxLength = 30,
                        pattern = "\\*|[A-Za-z0-9][A-Za-z0-9._-]{0,29}",
                        example = "2.0",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                String version,
        @Schema(
                        description = "文本分片 Unicode 码点上限，100 至 8000；章节末片可更短",
                        defaultValue = "" + KnowledgeLimits.DEFAULT_CHUNK_SIZE,
                        minimum = "100",
                        maximum = "8000")
                int chunkSize,
        @Schema(
                        description = "同章节重叠码点数，非负且小于等于 chunkSize-10",
                        defaultValue = "" + KnowledgeLimits.DEFAULT_CHUNK_OVERLAP,
                        minimum = "0")
                int overlap) {}
