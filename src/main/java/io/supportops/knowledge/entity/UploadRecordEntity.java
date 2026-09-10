package io.supportops.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 服务端受理的上传请求及成功、失败和重复命中记录。 */
@TableName("document_uploads")
public class UploadRecordEntity {
    /** 记录所属用户 UUID；历史及内部迁移记录为 null。 */
    private String userId;
    /** 返回记录所属用户，历史未归属时为空。 */
    public String getUserId() {
        return userId;
    }
    /** 设置服务端确认的记录所属用户。 */
    public void setUserId(String userId) {
        this.userId = userId;
    }

    /** 上传 UUID。 */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 幂等请求键。 */
    private String requestKey;

    /** 文件及参数摘要，防止幂等键复用。 */
    private String requestHash;

    /** 关联逻辑文档，受理失败时可空。 */
    private String documentId;

    /** 关联修订，尚未创建时可空。 */
    private String versionId;

    /** 本次处理批次，尚未创建时可空。 */
    private String batchId;

    /** 原件，保存前可空。 */
    private String fileId;

    /** 上传文件名。 */
    private String filename;

    /** 文件字节数。 */
    private Long sizeBytes;

    /** 原始字节摘要。 */
    private String checksum;

    /** API、WORKBENCH、EXAMPLE 或 MIGRATION。 */
    private String source;

    /** RECEIVING、ACCEPTED、DUPLICATE、SUCCEEDED 或 FAILED。 */
    private String status;

    /** 脱敏错误码，无错误为空。 */
    private String errorCode;

    /** 请求受理时间。 */
    private String createdAt;

    /** 结束时间，未完成时可空。 */
    private String finishedAt;

    /** 创建供 Mapper 使用的实体。 */
    public UploadRecordEntity() {}

    /** 返回上传 UUID。 */
    public String getId() {
        return id;
    }

    /** 设置上传 UUID。 */
    public void setId(String id) {
        this.id = id;
    }

    /** 返回幂等请求键。 */
    public String getRequestKey() {
        return requestKey;
    }

    /** 设置幂等请求键。 */
    public void setRequestKey(String requestKey) {
        this.requestKey = requestKey;
    }

    /** 返回文件及参数摘要，防止幂等键复用。 */
    public String getRequestHash() {
        return requestHash;
    }

    /** 设置文件及参数摘要，防止幂等键复用。 */
    public void setRequestHash(String requestHash) {
        this.requestHash = requestHash;
    }

    /** 返回关联逻辑文档，受理失败时可空。 */
    public String getDocumentId() {
        return documentId;
    }

    /** 设置关联逻辑文档，受理失败时可空。 */
    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }

    /** 返回关联修订，尚未创建时可空。 */
    public String getVersionId() {
        return versionId;
    }

    /** 设置关联修订，尚未创建时可空。 */
    public void setVersionId(String versionId) {
        this.versionId = versionId;
    }

    /** 返回本次处理批次，尚未创建时可空。 */
    public String getBatchId() {
        return batchId;
    }

    /** 设置本次处理批次，尚未创建时可空。 */
    public void setBatchId(String batchId) {
        this.batchId = batchId;
    }

    /** 返回原件，保存前可空。 */
    public String getFileId() {
        return fileId;
    }

    /** 设置原件，保存前可空。 */
    public void setFileId(String fileId) {
        this.fileId = fileId;
    }

    /** 返回上传文件名。 */
    public String getFilename() {
        return filename;
    }

    /** 设置上传文件名。 */
    public void setFilename(String filename) {
        this.filename = filename;
    }

    /** 返回文件字节数。 */
    public Long getSizeBytes() {
        return sizeBytes;
    }

    /** 设置文件字节数。 */
    public void setSizeBytes(Long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    /** 返回原始字节摘要。 */
    public String getChecksum() {
        return checksum;
    }

    /** 设置原始字节摘要。 */
    public void setChecksum(String checksum) {
        this.checksum = checksum;
    }

    /** 返回API、WORKBENCH、EXAMPLE 或 MIGRATION。 */
    public String getSource() {
        return source;
    }

    /** 设置API、WORKBENCH、EXAMPLE 或 MIGRATION。 */
    public void setSource(String source) {
        this.source = source;
    }

    /** 返回RECEIVING、ACCEPTED、DUPLICATE、SUCCEEDED 或 FAILED。 */
    public String getStatus() {
        return status;
    }

    /** 设置RECEIVING、ACCEPTED、DUPLICATE、SUCCEEDED 或 FAILED。 */
    public void setStatus(String status) {
        this.status = status;
    }

    /** 返回脱敏错误码，无错误为空。 */
    public String getErrorCode() {
        return errorCode;
    }

    /** 设置脱敏错误码，无错误为空。 */
    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    /** 返回请求受理时间。 */
    public String getCreatedAt() {
        return createdAt;
    }

    /** 设置请求受理时间。 */
    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    /** 返回结束时间，未完成时可空。 */
    public String getFinishedAt() {
        return finishedAt;
    }

    /** 设置结束时间，未完成时可空。 */
    public void setFinishedAt(String finishedAt) {
        this.finishedAt = finishedAt;
    }
}
