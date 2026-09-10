package io.supportops.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 原始文件在 MinIO 中的不可变对象记录。 */
@TableName("source_files")
public class SourceFileEntity {
    /** 所属逻辑文档；用于回收上传中断后未绑定修订的对象。 */
    private String documentId;

    /** 返回所属逻辑文档。 */
    public String getDocumentId() {
        return documentId;
    }

    /** 设置所属逻辑文档。 */
    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }

    /** 文件 UUID。 */
    @TableId(type = IdType.INPUT)
    private String id;

    /** MinIO 对象键。 */
    private String objectKey;

    /** 原始文件名。 */
    private String filename;

    /** 文件媒体类型。 */
    private String mediaType;

    /** 文件大小，字节。 */
    private Long sizeBytes;

    /** 原始字节 SHA-256。 */
    private String checksum;

    /** STORED、MISSING 或 DELETED。 */
    private String status;

    /** 创建时间，UTC ISO 8601。 */
    private String createdAt;

    /** 创建供 Mapper 使用的实体。 */
    public SourceFileEntity() {}

    /** 返回文件 UUID。 */
    public String getId() {
        return id;
    }

    /** 设置文件 UUID。 */
    public void setId(String id) {
        this.id = id;
    }

    /** 返回MinIO 对象键。 */
    public String getObjectKey() {
        return objectKey;
    }

    /** 设置MinIO 对象键。 */
    public void setObjectKey(String objectKey) {
        this.objectKey = objectKey;
    }

    /** 返回原始文件名。 */
    public String getFilename() {
        return filename;
    }

    /** 设置原始文件名。 */
    public void setFilename(String filename) {
        this.filename = filename;
    }

    /** 返回文件媒体类型。 */
    public String getMediaType() {
        return mediaType;
    }

    /** 设置文件媒体类型。 */
    public void setMediaType(String mediaType) {
        this.mediaType = mediaType;
    }

    /** 返回文件大小，字节。 */
    public Long getSizeBytes() {
        return sizeBytes;
    }

    /** 设置文件大小，字节。 */
    public void setSizeBytes(Long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    /** 返回原始字节 SHA-256。 */
    public String getChecksum() {
        return checksum;
    }

    /** 设置原始字节 SHA-256。 */
    public void setChecksum(String checksum) {
        this.checksum = checksum;
    }

    /** 返回STORED、MISSING 或 DELETED。 */
    public String getStatus() {
        return status;
    }

    /** 设置STORED、MISSING 或 DELETED。 */
    public void setStatus(String status) {
        this.status = status;
    }

    /** 返回创建时间，UTC ISO 8601。 */
    public String getCreatedAt() {
        return createdAt;
    }

    /** 设置创建时间，UTC ISO 8601。 */
    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }
}
