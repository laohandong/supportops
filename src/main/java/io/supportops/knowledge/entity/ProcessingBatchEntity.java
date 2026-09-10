package io.supportops.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 一次解析、分片或 Excel 导入配置及发布状态。 */
@TableName("document_batches")
public class ProcessingBatchEntity {
    /** 同一修订内单调递增的处理代，避免依赖各实例的时钟排序。 */
    private Integer generation = 1;

    /** 返回修订内处理代。 */
    public Integer getGeneration() {
        return generation;
    }

    /** 设置在文档锁内分配的处理代。 */
    public void setGeneration(Integer generation) {
        this.generation = generation;
    }

    /** 批次 UUID。 */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 逻辑文档 UUID。 */
    private String documentId;

    /** 修订 UUID。 */
    private String versionId;

    /** 分片字符上限，Excel 不使用。 */
    private Integer chunkSize;

    /** 相邻分片重叠字符数。 */
    private Integer overlap;

    /** 导入配置 JSON：Sheet、表头及列类型。 */
    private String configJson;

    /** 解析配置摘要。 */
    private String configHash;

    /** 解析器版本。 */
    private String parserVersion;

    /** PENDING、RUNNING、PREVIEW、READY、FAILED 或 CANCELLED。 */
    private String status;

    /** 文本索引状态；Excel 为 NOT_APPLICABLE。 */
    private String textStatus;

    /** 向量任务汇总状态；Excel 为 NOT_APPLICABLE。 */
    private String vectorStatus;

    /** 文本分片数量。 */
    private Integer chunkCount;

    /** 已导入 Excel 行数。 */
    private Long rowCount;

    /** 脱敏错误码。 */
    private String errorCode;

    /** 批次创建时间。 */
    private String createdAt;

    /** 创建供 Mapper 使用的实体。 */
    public ProcessingBatchEntity() {}

    /** 返回批次 UUID。 */
    public String getId() {
        return id;
    }

    /** 设置批次 UUID。 */
    public void setId(String id) {
        this.id = id;
    }

    /** 返回逻辑文档 UUID。 */
    public String getDocumentId() {
        return documentId;
    }

    /** 设置逻辑文档 UUID。 */
    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }

    /** 返回修订 UUID。 */
    public String getVersionId() {
        return versionId;
    }

    /** 设置修订 UUID。 */
    public void setVersionId(String versionId) {
        this.versionId = versionId;
    }

    /** 返回分片字符上限，Excel 不使用。 */
    public Integer getChunkSize() {
        return chunkSize;
    }

    /** 设置分片字符上限，Excel 不使用。 */
    public void setChunkSize(Integer chunkSize) {
        this.chunkSize = chunkSize;
    }

    /** 返回相邻分片重叠字符数。 */
    public Integer getOverlap() {
        return overlap;
    }

    /** 设置相邻分片重叠字符数。 */
    public void setOverlap(Integer overlap) {
        this.overlap = overlap;
    }

    /** 返回导入配置 JSON：Sheet、表头及列类型。 */
    public String getConfigJson() {
        return configJson;
    }

    /** 设置导入配置 JSON：Sheet、表头及列类型。 */
    public void setConfigJson(String configJson) {
        this.configJson = configJson;
    }

    /** 返回解析配置摘要。 */
    public String getConfigHash() {
        return configHash;
    }

    /** 设置解析配置摘要。 */
    public void setConfigHash(String configHash) {
        this.configHash = configHash;
    }

    /** 返回解析器版本。 */
    public String getParserVersion() {
        return parserVersion;
    }

    /** 设置解析器版本。 */
    public void setParserVersion(String parserVersion) {
        this.parserVersion = parserVersion;
    }

    /** 返回PENDING、RUNNING、PREVIEW、READY、FAILED 或 CANCELLED。 */
    public String getStatus() {
        return status;
    }

    /** 设置PENDING、RUNNING、PREVIEW、READY、FAILED 或 CANCELLED。 */
    public void setStatus(String status) {
        this.status = status;
    }

    /** 返回文本索引状态；Excel 为 NOT_APPLICABLE。 */
    public String getTextStatus() {
        return textStatus;
    }

    /** 设置文本索引状态；Excel 为 NOT_APPLICABLE。 */
    public void setTextStatus(String textStatus) {
        this.textStatus = textStatus;
    }

    /** 返回向量任务汇总状态；Excel 为 NOT_APPLICABLE。 */
    public String getVectorStatus() {
        return vectorStatus;
    }

    /** 设置向量任务汇总状态；Excel 为 NOT_APPLICABLE。 */
    public void setVectorStatus(String vectorStatus) {
        this.vectorStatus = vectorStatus;
    }

    /** 返回文本分片数量。 */
    public Integer getChunkCount() {
        return chunkCount;
    }

    /** 设置文本分片数量。 */
    public void setChunkCount(Integer chunkCount) {
        this.chunkCount = chunkCount;
    }

    /** 返回已导入 Excel 行数。 */
    public Long getRowCount() {
        return rowCount;
    }

    /** 设置已导入 Excel 行数。 */
    public void setRowCount(Long rowCount) {
        this.rowCount = rowCount;
    }

    /** 返回脱敏错误码。 */
    public String getErrorCode() {
        return errorCode;
    }

    /** 设置脱敏错误码。 */
    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    /** 返回批次创建时间。 */
    public String getCreatedAt() {
        return createdAt;
    }

    /** 设置批次创建时间。 */
    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }
}
