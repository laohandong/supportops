package io.supportops.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 知识文档持久化实体，保持原有 UUID 和 ISO 8601 时间格式。 */
@TableName("documents")
public class KnowledgeDocumentEntity {
    /** 活跃内容去重键；删除后为空，允许再次上传形成新文档。 */
    private String canonicalKey;

    /** 返回活跃内容去重键。 */
    public String getCanonicalKey() {
        return canonicalKey;
    }

    /** 设置活跃内容去重键。 */
    public void setCanonicalKey(String canonicalKey) {
        this.canonicalKey = canonicalKey;
    }

    /** 文档 UUID。 */
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    /** 文档标题。 */
    @TableField("title")
    private String title;

    /** 适用版本，星号表示通用资料。 */
    @TableField("version")
    private String version;

    /** 去除目录后的文件名。 */
    @TableField("filename")
    private String filename;

    /** 原始文件 SHA-256 摘要。 */
    @TableField("checksum")
    private String checksum;

    /** 创建时间，ISO 8601 字符串。 */
    @TableField("created_at")
    private String createdAt;

    /** 向量端点与模型指纹，未建索引时为空字符串。 */
    @TableField("embedding_key")
    private String embeddingKey;

    /** 创建供 MyBatis 使用的空实体。 */
    public KnowledgeDocumentEntity() {}

    /** 使用明确字段创建实体，调用方负责业务校验。 */
    public KnowledgeDocumentEntity(
            String id,
            String title,
            String version,
            String filename,
            String checksum,
            String createdAt,
            String embeddingKey) {
        this.id = id;
        this.title = title;
        this.version = version;
        this.filename = filename;
        this.checksum = checksum;
        this.createdAt = createdAt;
        this.embeddingKey = embeddingKey;
    }

    /** 返回文档 UUID。 */
    public String getId() {
        return id;
    }

    /** 设置文档 UUID。 */
    public void setId(String id) {
        this.id = id;
    }

    /** 返回文档标题。 */
    public String getTitle() {
        return title;
    }

    /** 设置文档标题。 */
    public void setTitle(String title) {
        this.title = title;
    }

    /** 返回适用版本，星号表示通用资料。 */
    public String getVersion() {
        return version;
    }

    /** 设置适用版本，星号表示通用资料。 */
    public void setVersion(String version) {
        this.version = version;
    }

    /** 返回去除目录后的文件名。 */
    public String getFilename() {
        return filename;
    }

    /** 设置去除目录后的文件名。 */
    public void setFilename(String filename) {
        this.filename = filename;
    }

    /** 返回原始文件 SHA-256 摘要。 */
    public String getChecksum() {
        return checksum;
    }

    /** 设置原始文件 SHA-256 摘要。 */
    public void setChecksum(String checksum) {
        this.checksum = checksum;
    }

    /** 返回创建时间，ISO 8601 字符串。 */
    public String getCreatedAt() {
        return createdAt;
    }

    /** 设置创建时间，ISO 8601 字符串。 */
    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    /** 返回向量端点与模型指纹，未建索引时为空字符串。 */
    public String getEmbeddingKey() {
        return embeddingKey;
    }

    /** 设置向量端点与模型指纹，未建索引时为空字符串。 */
    public void setEmbeddingKey(String embeddingKey) {
        this.embeddingKey = embeddingKey;
    }

    /** 逻辑删除标记。 */
    private Boolean deleted;

    /** 返回逻辑删除标记。 */
    public Boolean getDeleted() {
        return deleted;
    }

    /** 设置逻辑删除标记。 */
    public void setDeleted(Boolean deleted) {
        this.deleted = deleted;
    }
}
