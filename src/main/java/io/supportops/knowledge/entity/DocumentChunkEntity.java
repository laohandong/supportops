package io.supportops.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 文档分片持久化实体，分片 ID 与已有引用保持一致。 */
@TableName("chunks")
public class DocumentChunkEntity {
    /** 文档 UUID 与分片序号组成的引用 ID。 */
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    /** 来源文档 UUID。 */
    @TableField("document_id")
    private String documentId;

    /** 标题、行号或 PDF 页码等原文位置。 */
    @TableField("location")
    private String location;

    /** 原始分片正文。 */
    @TableField("content")
    private String content;

    /** 向量 JSON，未建立索引时为 null。 */
    @TableField("embedding")
    private String embedding;

    /** 创建供 MyBatis 使用的空实体。 */
    public DocumentChunkEntity() {}

    /** 使用明确字段创建实体，调用方负责业务校验。 */
    public DocumentChunkEntity(
            String id, String documentId, String location, String content, String embedding) {
        this.id = id;
        this.documentId = documentId;
        this.location = location;
        this.content = content;
        this.embedding = embedding;
    }

    /** 返回文档 UUID 与分片序号组成的引用 ID。 */
    public String getId() {
        return id;
    }

    /** 设置文档 UUID 与分片序号组成的引用 ID。 */
    public void setId(String id) {
        this.id = id;
    }

    /** 返回来源文档 UUID。 */
    public String getDocumentId() {
        return documentId;
    }

    /** 设置来源文档 UUID。 */
    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }

    /** 返回标题、行号或 PDF 页码等原文位置。 */
    public String getLocation() {
        return location;
    }

    /** 设置标题、行号或 PDF 页码等原文位置。 */
    public void setLocation(String location) {
        this.location = location;
    }

    /** 返回原始分片正文。 */
    public String getContent() {
        return content;
    }

    /** 设置原始分片正文。 */
    public void setContent(String content) {
        this.content = content;
    }

    /** 返回向量 JSON，未建立索引时为 null。 */
    public String getEmbedding() {
        return embedding;
    }

    /** 设置向量 JSON，未建立索引时为 null。 */
    public void setEmbedding(String embedding) {
        this.embedding = embedding;
    }

    /** 来源修订 UUID。 */
    private String versionId;

    /** 返回来源修订 UUID。 */
    public String getVersionId() {
        return versionId;
    }

    /** 设置来源修订 UUID。 */
    public void setVersionId(String versionId) {
        this.versionId = versionId;
    }

    /** 处理批次 UUID。 */
    private String batchId;

    /** 返回处理批次 UUID。 */
    public String getBatchId() {
        return batchId;
    }

    /** 设置处理批次 UUID。 */
    public void setBatchId(String batchId) {
        this.batchId = batchId;
    }

    /** 从 1 开始的数值序号。 */
    private Integer chunkIndex;

    /** 返回从 1 开始的数值序号。 */
    public Integer getChunkIndex() {
        return chunkIndex;
    }

    /** 设置从 1 开始的数值序号。 */
    public void setChunkIndex(Integer chunkIndex) {
        this.chunkIndex = chunkIndex;
    }

    /** 章节标题。 */
    private String heading;

    /** 返回章节标题。 */
    public String getHeading() {
        return heading;
    }

    /** 设置章节标题。 */
    public void setHeading(String heading) {
        this.heading = heading;
    }

    /** 章节层级路径。 */
    private String headingPath;

    /** 返回章节层级路径。 */
    public String getHeadingPath() {
        return headingPath;
    }

    /** 设置章节层级路径。 */
    public void setHeadingPath(String headingPath) {
        this.headingPath = headingPath;
    }

    /** 开始页码，从 1 开始，可空。 */
    private Integer pageStart;

    /** 返回开始页码，从 1 开始，可空。 */
    public Integer getPageStart() {
        return pageStart;
    }

    /** 设置开始页码，从 1 开始，可空。 */
    public void setPageStart(Integer pageStart) {
        this.pageStart = pageStart;
    }

    /** 结束页码，可空。 */
    private Integer pageEnd;

    /** 返回结束页码，可空。 */
    public Integer getPageEnd() {
        return pageEnd;
    }

    /** 设置结束页码，可空。 */
    public void setPageEnd(Integer pageEnd) {
        this.pageEnd = pageEnd;
    }

    /** 开始行号，从 1 开始，可空。 */
    private Integer lineStart;

    /** 返回开始行号，从 1 开始，可空。 */
    public Integer getLineStart() {
        return lineStart;
    }

    /** 设置开始行号，从 1 开始，可空。 */
    public void setLineStart(Integer lineStart) {
        this.lineStart = lineStart;
    }

    /** 结束行号，可空。 */
    private Integer lineEnd;

    /** 返回结束行号，可空。 */
    public Integer getLineEnd() {
        return lineEnd;
    }

    /** 设置结束行号，可空。 */
    public void setLineEnd(Integer lineEnd) {
        this.lineEnd = lineEnd;
    }

    /** 章节或页内码点起点，零基。 */
    private Integer charStart;

    /** 返回章节或页内码点起点，零基。 */
    public Integer getCharStart() {
        return charStart;
    }

    /** 设置章节或页内码点起点，零基。 */
    public void setCharStart(Integer charStart) {
        this.charStart = charStart;
    }

    /** 章节或页内码点终点，不含。 */
    private Integer charEnd;

    /** 返回章节或页内码点终点，不含。 */
    public Integer getCharEnd() {
        return charEnd;
    }

    /** 设置章节或页内码点终点，不含。 */
    public void setCharEnd(Integer charEnd) {
        this.charEnd = charEnd;
    }

    /** 分片 UTF-8 SHA-256。 */
    private String contentHash;

    /** 返回分片 UTF-8 SHA-256。 */
    public String getContentHash() {
        return contentHash;
    }

    /** 设置分片 UTF-8 SHA-256。 */
    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    /** 分片 Unicode 码点数。 */
    private Integer charCount;

    /** 返回分片 Unicode 码点数。 */
    public Integer getCharCount() {
        return charCount;
    }

    /** 设置分片 Unicode 码点数。 */
    public void setCharCount(Integer charCount) {
        this.charCount = charCount;
    }
}
