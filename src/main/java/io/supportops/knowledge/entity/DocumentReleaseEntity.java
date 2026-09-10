package io.supportops.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 每份文档在每个适用产品版本中的当前可检索快照。 */
@TableName("document_releases")
public class DocumentReleaseEntity {
    /** 发布关系 UUID。 */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 逻辑文档 UUID。 */
    private String documentId;

    /** 适用产品版本或 *。 */
    private String productVersion;

    /** 已发布修订 UUID。 */
    private String versionId;

    /** 已发布处理批次 UUID。 */
    private String batchId;

    /** 生效向量任务；没有时可空。 */
    private String vectorTaskId;

    /** 最后发布时间。 */
    private String updatedAt;

    /** 创建供 Mapper 使用的实体。 */
    public DocumentReleaseEntity() {}

    /** 返回发布关系 UUID。 */
    public String getId() {
        return id;
    }

    /** 设置发布关系 UUID。 */
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

    /** 返回适用产品版本或 *。 */
    public String getProductVersion() {
        return productVersion;
    }

    /** 设置适用产品版本或 *。 */
    public void setProductVersion(String productVersion) {
        this.productVersion = productVersion;
    }

    /** 返回已发布修订 UUID。 */
    public String getVersionId() {
        return versionId;
    }

    /** 设置已发布修订 UUID。 */
    public void setVersionId(String versionId) {
        this.versionId = versionId;
    }

    /** 返回已发布处理批次 UUID。 */
    public String getBatchId() {
        return batchId;
    }

    /** 设置已发布处理批次 UUID。 */
    public void setBatchId(String batchId) {
        this.batchId = batchId;
    }

    /** 返回生效向量任务；没有时可空。 */
    public String getVectorTaskId() {
        return vectorTaskId;
    }

    /** 设置生效向量任务；没有时可空。 */
    public void setVectorTaskId(String vectorTaskId) {
        this.vectorTaskId = vectorTaskId;
    }

    /** 返回最后发布时间。 */
    public String getUpdatedAt() {
        return updatedAt;
    }

    /** 设置最后发布时间。 */
    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }
}
