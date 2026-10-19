package io.supportops.integration.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 访问密钥摘要的持久化映射，不直接作为接口响应。 */
@TableName("application_access_keys")
public class ApplicationAccessKeyEntity {
    /** 资源主键。 */
    @TableId(value = "id", type = IdType.INPUT)
    private String id;
    /** 应用 UUID。 */
    private String applicationId;
    /** 高熵秘密SHA256，不保存原文。 */
    private String secretHash;
    /** UTC到期时间。 */
    private String expiresAt;
    /** 明确资源状态。 */
    private String status;
    /** 签发管理员 UUID。 */
    private String createdBy;
    /** UTC ISO 8601 创建时间。 */
    private String createdAt;

    /** 创建空映射对象，由业务服务显式校验并设置字段。 */
    public ApplicationAccessKeyEntity() {}

    /** 返回资源主键。 */
    public String getId() {
        return id;
    }

    /** 设置资源主键，调用方负责业务约束。 */
    public void setId(String value) {
        this.id = value;
    }

    /** 返回应用 UUID。 */
    public String getApplicationId() {
        return applicationId;
    }

    /** 设置应用 UUID，调用方负责业务约束。 */
    public void setApplicationId(String value) {
        this.applicationId = value;
    }

    /** 返回高熵秘密SHA256，不保存原文。 */
    public String getSecretHash() {
        return secretHash;
    }

    /** 设置高熵秘密SHA256，不保存原文，调用方负责业务约束。 */
    public void setSecretHash(String value) {
        this.secretHash = value;
    }

    /** 返回UTC到期时间。 */
    public String getExpiresAt() {
        return expiresAt;
    }

    /** 设置UTC到期时间，调用方负责业务约束。 */
    public void setExpiresAt(String value) {
        this.expiresAt = value;
    }

    /** 返回明确资源状态。 */
    public String getStatus() {
        return status;
    }

    /** 设置明确资源状态，调用方负责业务约束。 */
    public void setStatus(String value) {
        this.status = value;
    }

    /** 返回签发管理员 UUID。 */
    public String getCreatedBy() {
        return createdBy;
    }

    /** 设置签发管理员 UUID，调用方负责业务约束。 */
    public void setCreatedBy(String value) {
        this.createdBy = value;
    }

    /** 返回UTC ISO 8601 创建时间。 */
    public String getCreatedAt() {
        return createdAt;
    }

    /** 设置UTC ISO 8601 创建时间，调用方负责业务约束。 */
    public void setCreatedAt(String value) {
        this.createdAt = value;
    }
    /** 人工批准或签发所属恢复代次。 */
    private Integer approvedGeneration;
    /** 返回批准所属恢复代次。 */
    public Integer getApprovedGeneration() { return approvedGeneration; }
    /** 设置批准所属恢复代次。 */
    public void setApprovedGeneration(Integer value) { approvedGeneration = value; }
}
