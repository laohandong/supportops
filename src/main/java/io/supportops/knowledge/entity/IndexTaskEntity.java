package io.supportops.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 带租约的持久化后台任务，调度与补偿共用同一领取入口。 */
@TableName("knowledge_tasks")
public class IndexTaskEntity {
    /** 任务 UUID。 */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 逻辑文档 UUID。 */
    private String documentId;

    /** 修订 UUID。 */
    private String versionId;

    /** 处理批次 UUID。 */
    private String batchId;

    /** IMPORT、TEXT、VECTOR 或 CLEANUP。 */
    private String kind;

    /** PENDING、RUNNING、SUCCEEDED、RETRY_WAIT、BLOCKED、FAILED、CANCELLED 或 SUPERSEDED。 */
    private String status;

    /** 当前执行阶段。 */
    private String stage;

    /** 端点及模型标识，不含凭据。 */
    private String profileKey;

    /** ES 目标索引，未分配时为空。 */
    private String indexName;

    /** 向量维度，未生成时为 0。 */
    private Integer dimensions;

    /** 已确认成功的片段数。 */
    private Integer completedChunks;

    /** 执行次数，包含首次执行。 */
    private Integer attemptCount;

    /** 下次执行的 UTC 毫秒时间戳。 */
    private Long nextRetryAt;

    /** 本次领取令牌。 */
    private String leaseOwner;

    /** 租约到期的 UTC 毫秒时间戳。 */
    private Long leaseUntil;

    /** 最近心跳的 UTC 毫秒时间戳。 */
    private Long heartbeatAt;

    /** 脱敏错误码。 */
    private String errorCode;

    /** 任务创建时间。 */
    private String createdAt;

    /** 终态时间。 */
    private String finishedAt;

    /** 创建供 Mapper 使用的实体。 */
    public IndexTaskEntity() {}

    /** 返回任务 UUID。 */
    public String getId() {
        return id;
    }

    /** 设置任务 UUID。 */
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

    /** 返回处理批次 UUID。 */
    public String getBatchId() {
        return batchId;
    }

    /** 设置处理批次 UUID。 */
    public void setBatchId(String batchId) {
        this.batchId = batchId;
    }

    /** 返回IMPORT、TEXT、VECTOR 或 CLEANUP。 */
    public String getKind() {
        return kind;
    }

    /** 设置IMPORT、TEXT、VECTOR 或 CLEANUP。 */
    public void setKind(String kind) {
        this.kind = kind;
    }

    /** 返回PENDING、RUNNING、SUCCEEDED、RETRY_WAIT、BLOCKED、FAILED、CANCELLED 或 SUPERSEDED。 */
    public String getStatus() {
        return status;
    }

    /** 设置PENDING、RUNNING、SUCCEEDED、RETRY_WAIT、BLOCKED、FAILED、CANCELLED 或 SUPERSEDED。 */
    public void setStatus(String status) {
        this.status = status;
    }

    /** 返回当前执行阶段。 */
    public String getStage() {
        return stage;
    }

    /** 设置当前执行阶段。 */
    public void setStage(String stage) {
        this.stage = stage;
    }

    /** 返回端点及模型标识，不含凭据。 */
    public String getProfileKey() {
        return profileKey;
    }

    /** 设置端点及模型标识，不含凭据。 */
    public void setProfileKey(String profileKey) {
        this.profileKey = profileKey;
    }

    /** 返回ES 目标索引，未分配时为空。 */
    public String getIndexName() {
        return indexName;
    }

    /** 设置ES 目标索引，未分配时为空。 */
    public void setIndexName(String indexName) {
        this.indexName = indexName;
    }

    /** 返回向量维度，未生成时为 0。 */
    public Integer getDimensions() {
        return dimensions;
    }

    /** 设置向量维度，未生成时为 0。 */
    public void setDimensions(Integer dimensions) {
        this.dimensions = dimensions;
    }

    /** 返回已确认成功的片段数。 */
    public Integer getCompletedChunks() {
        return completedChunks;
    }

    /** 设置已确认成功的片段数。 */
    public void setCompletedChunks(Integer completedChunks) {
        this.completedChunks = completedChunks;
    }

    /** 返回执行次数，包含首次执行。 */
    public Integer getAttemptCount() {
        return attemptCount;
    }

    /** 当前人工重试预算的起始累计次数，首次为 0。 */
    private Integer budgetStart = 0;

    /** 返回当前重试预算的起始次数。 */
    public Integer getBudgetStart() {
        return budgetStart;
    }

    /** 重置预算但不清除尝试历史。 */
    public void setBudgetStart(Integer budgetStart) {
        this.budgetStart = budgetStart;
    }

    /** 设置执行次数，包含首次执行。 */
    public void setAttemptCount(Integer attemptCount) {
        this.attemptCount = attemptCount;
    }

    /** 返回下次执行的 UTC 毫秒时间戳。 */
    public Long getNextRetryAt() {
        return nextRetryAt;
    }

    /** 设置下次执行的 UTC 毫秒时间戳。 */
    public void setNextRetryAt(Long nextRetryAt) {
        this.nextRetryAt = nextRetryAt;
    }

    /** 返回本次领取令牌。 */
    public String getLeaseOwner() {
        return leaseOwner;
    }

    /** 设置本次领取令牌。 */
    public void setLeaseOwner(String leaseOwner) {
        this.leaseOwner = leaseOwner;
    }

    /** 返回租约到期的 UTC 毫秒时间戳。 */
    public Long getLeaseUntil() {
        return leaseUntil;
    }

    /** 设置租约到期的 UTC 毫秒时间戳。 */
    public void setLeaseUntil(Long leaseUntil) {
        this.leaseUntil = leaseUntil;
    }

    /** 返回最近心跳的 UTC 毫秒时间戳。 */
    public Long getHeartbeatAt() {
        return heartbeatAt;
    }

    /** 设置最近心跳的 UTC 毫秒时间戳。 */
    public void setHeartbeatAt(Long heartbeatAt) {
        this.heartbeatAt = heartbeatAt;
    }

    /** 返回脱敏错误码。 */
    public String getErrorCode() {
        return errorCode;
    }

    /** 设置脱敏错误码。 */
    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    /** 返回任务创建时间。 */
    public String getCreatedAt() {
        return createdAt;
    }

    /** 设置任务创建时间。 */
    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    /** 返回终态时间。 */
    public String getFinishedAt() {
        return finishedAt;
    }

    /** 设置终态时间。 */
    public void setFinishedAt(String finishedAt) {
        this.finishedAt = finishedAt;
    }
}
