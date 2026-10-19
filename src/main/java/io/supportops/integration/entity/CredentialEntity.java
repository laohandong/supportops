package io.supportops.integration.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 加密凭据的持久化映射，不直接作为接口响应。 */
@TableName("credentials")
public class CredentialEntity {
    /** 资源主键。 */
    @TableId(value = "id", type = IdType.INPUT)
    private String id;
    /** 秘密用途。 */
    private String purpose;
    /** 用途所属资源。 */
    private String ownerId;
    /** 资源内修订号。 */
    private Integer revision;
    /** 主密钥版本。 */
    private String keyId;
    /** 加密时部署标识，AAD不可改写。 */
    private String encryptionOriginDeploymentId;
    /** 96位nonce的Base64。 */
    private String nonce;
    /** 密文Base64，仅内部使用。 */
    private String ciphertext;
    /** 128位认证标签Base64。 */
    private String tag;
    /** 明确资源状态。 */
    private String status;
    /** UTC ISO 8601 创建时间。 */
    private String createdAt;

    /** 创建空映射对象，由业务服务显式校验并设置字段。 */
    public CredentialEntity() {}

    /** 返回资源主键。 */
    public String getId() {
        return id;
    }

    /** 设置资源主键，调用方负责业务约束。 */
    public void setId(String value) {
        this.id = value;
    }

    /** 返回秘密用途。 */
    public String getPurpose() {
        return purpose;
    }

    /** 设置秘密用途，调用方负责业务约束。 */
    public void setPurpose(String value) {
        this.purpose = value;
    }

    /** 返回用途所属资源。 */
    public String getOwnerId() {
        return ownerId;
    }

    /** 设置用途所属资源，调用方负责业务约束。 */
    public void setOwnerId(String value) {
        this.ownerId = value;
    }

    /** 返回资源内修订号。 */
    public Integer getRevision() {
        return revision;
    }

    /** 设置资源内修订号，调用方负责业务约束。 */
    public void setRevision(Integer value) {
        this.revision = value;
    }

    /** 返回主密钥版本。 */
    public String getKeyId() {
        return keyId;
    }

    /** 设置主密钥版本，调用方负责业务约束。 */
    public void setKeyId(String value) {
        this.keyId = value;
    }

    /** 返回加密时部署标识，AAD不可改写。 */
    public String getEncryptionOriginDeploymentId() {
        return encryptionOriginDeploymentId;
    }

    /** 设置加密时部署标识，AAD不可改写，调用方负责业务约束。 */
    public void setEncryptionOriginDeploymentId(String value) {
        this.encryptionOriginDeploymentId = value;
    }

    /** 返回96位nonce的Base64。 */
    public String getNonce() {
        return nonce;
    }

    /** 设置96位nonce的Base64，调用方负责业务约束。 */
    public void setNonce(String value) {
        this.nonce = value;
    }

    /** 返回密文Base64，仅内部使用。 */
    public String getCiphertext() {
        return ciphertext;
    }

    /** 设置密文Base64，仅内部使用，调用方负责业务约束。 */
    public void setCiphertext(String value) {
        this.ciphertext = value;
    }

    /** 返回128位认证标签Base64。 */
    public String getTag() {
        return tag;
    }

    /** 设置128位认证标签Base64，调用方负责业务约束。 */
    public void setTag(String value) {
        this.tag = value;
    }

    /** 返回明确资源状态。 */
    public String getStatus() {
        return status;
    }

    /** 设置明确资源状态，调用方负责业务约束。 */
    public void setStatus(String value) {
        this.status = value;
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
