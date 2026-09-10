package io.supportops.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 逻辑文档的不可变修订及适用产品版本。 */
@TableName("document_versions")
public class DocumentVersionEntity {
    /** 修订 UUID。 */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 逻辑文档 UUID。 */
    private String documentId;

    /** 文档内递增修订号。 */
    private Integer revision;

    /** 精确适用版本或通用标记 *。 */
    private String productVersion;

    /** 该修订的标题快照。 */
    private String title;

    /** 原件 UUID。 */
    private String fileId;

    /** MD、PDF、XLS 或 XLSX。 */
    private String fileType;

    /** 修订说明，空字符串表示未提供。 */
    private String note;

    /** 创建时间，UTC ISO 8601。 */
    private String createdAt;

    /** 创建供 Mapper 使用的实体。 */
    public DocumentVersionEntity() {}

    /** 返回修订 UUID。 */
    public String getId() {
        return id;
    }

    /** 设置修订 UUID。 */
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

    /** 返回文档内递增修订号。 */
    public Integer getRevision() {
        return revision;
    }

    /** 设置文档内递增修订号。 */
    public void setRevision(Integer revision) {
        this.revision = revision;
    }

    /** 返回精确适用版本或通用标记 *。 */
    public String getProductVersion() {
        return productVersion;
    }

    /** 设置精确适用版本或通用标记 *。 */
    public void setProductVersion(String productVersion) {
        this.productVersion = productVersion;
    }

    /** 返回该修订的标题快照。 */
    public String getTitle() {
        return title;
    }

    /** 设置该修订的标题快照。 */
    public void setTitle(String title) {
        this.title = title;
    }

    /** 返回原件 UUID。 */
    public String getFileId() {
        return fileId;
    }

    /** 设置原件 UUID。 */
    public void setFileId(String fileId) {
        this.fileId = fileId;
    }

    /** 返回MD、PDF、XLS 或 XLSX。 */
    public String getFileType() {
        return fileType;
    }

    /** 设置MD、PDF、XLS 或 XLSX。 */
    public void setFileType(String fileType) {
        this.fileType = fileType;
    }

    /** 返回修订说明，空字符串表示未提供。 */
    public String getNote() {
        return note;
    }

    /** 设置修订说明，空字符串表示未提供。 */
    public void setNote(String note) {
        this.note = note;
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
