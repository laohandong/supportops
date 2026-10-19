package io.supportops.integration.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 不可变连接修订的持久化映射，不直接作为接口响应。 */
@TableName("connector_revisions")
public class ConnectorRevisionEntity {
    /** 资源主键。 */
    @TableId(value = "id", type = IdType.INPUT)
    private String id;
    /** 目标环境 UUID。 */
    private String environmentId;
    /** 资源内修订号。 */
    private Integer revision;
    /** 固定连接类型。 */
    private String type;
    /** 无秘密的配置JSON。 */
    private String configJson;
    /** 秘密引用 UUID。 */
    private String credentialId;
    /** 配置SHA256。 */
    private String digest;
    /** UTC ISO 8601 创建时间。 */
    private String createdAt;

    /** 创建空映射对象，由业务服务显式校验并设置字段。 */
    public ConnectorRevisionEntity() {}

    /** 返回资源主键。 */
    public String getId() {
        return id;
    }

    /** 设置资源主键，调用方负责业务约束。 */
    public void setId(String value) {
        this.id = value;
    }

    /** 返回目标环境 UUID。 */
    public String getEnvironmentId() {
        return environmentId;
    }

    /** 设置目标环境 UUID，调用方负责业务约束。 */
    public void setEnvironmentId(String value) {
        this.environmentId = value;
    }

    /** 返回资源内修订号。 */
    public Integer getRevision() {
        return revision;
    }

    /** 设置资源内修订号，调用方负责业务约束。 */
    public void setRevision(Integer value) {
        this.revision = value;
    }

    /** 返回固定连接类型。 */
    public String getType() {
        return type;
    }

    /** 设置固定连接类型，调用方负责业务约束。 */
    public void setType(String value) {
        this.type = value;
    }

    /** 返回无秘密的配置JSON。 */
    public String getConfigJson() {
        return configJson;
    }

    /** 设置无秘密的配置JSON，调用方负责业务约束。 */
    public void setConfigJson(String value) {
        this.configJson = value;
    }

    /** 返回秘密引用 UUID。 */
    public String getCredentialId() {
        return credentialId;
    }

    /** 设置秘密引用 UUID，调用方负责业务约束。 */
    public void setCredentialId(String value) {
        this.credentialId = value;
    }

    /** 返回配置SHA256。 */
    public String getDigest() {
        return digest;
    }

    /** 设置配置SHA256，调用方负责业务约束。 */
    public void setDigest(String value) {
        this.digest = value;
    }

    /** 返回UTC ISO 8601 创建时间。 */
    public String getCreatedAt() {
        return createdAt;
    }

    /** 设置UTC ISO 8601 创建时间，调用方负责业务约束。 */
    public void setCreatedAt(String value) {
        this.createdAt = value;
    }
}
