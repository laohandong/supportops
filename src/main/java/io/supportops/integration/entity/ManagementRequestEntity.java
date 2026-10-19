package io.supportops.integration.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 安全管理回执的持久化映射，不直接作为接口响应。 */
@TableName("integration_management_requests")
public class ManagementRequestEntity {
    /** 资源主键。 */
    @TableId(value = "id", type = IdType.INPUT)
    private String id;
    /** 管理员 UUID。 */
    private String actorUserId;
    /** 固定请求 UUID。 */
    private String requestId;
    /** 明确管理操作。 */
    private String operation;
    /** 请求SHA256。 */
    private String payloadHash;
    /** 关联资源 UUID。 */
    private String resourceId;
    /** 不含秘密的回执。 */
    private String receiptJson;
    /** UTC ISO 8601 创建时间。 */
    private String createdAt;

    /** 创建空映射对象，由业务服务显式校验并设置字段。 */
    public ManagementRequestEntity() {}

    /** 返回资源主键。 */
    public String getId() {
        return id;
    }

    /** 设置资源主键，调用方负责业务约束。 */
    public void setId(String value) {
        this.id = value;
    }

    /** 返回管理员 UUID。 */
    public String getActorUserId() {
        return actorUserId;
    }

    /** 设置管理员 UUID，调用方负责业务约束。 */
    public void setActorUserId(String value) {
        this.actorUserId = value;
    }

    /** 返回固定请求 UUID。 */
    public String getRequestId() {
        return requestId;
    }

    /** 设置固定请求 UUID，调用方负责业务约束。 */
    public void setRequestId(String value) {
        this.requestId = value;
    }

    /** 返回明确管理操作。 */
    public String getOperation() {
        return operation;
    }

    /** 设置明确管理操作，调用方负责业务约束。 */
    public void setOperation(String value) {
        this.operation = value;
    }

    /** 返回请求SHA256。 */
    public String getPayloadHash() {
        return payloadHash;
    }

    /** 设置请求SHA256，调用方负责业务约束。 */
    public void setPayloadHash(String value) {
        this.payloadHash = value;
    }

    /** 返回关联资源 UUID。 */
    public String getResourceId() {
        return resourceId;
    }

    /** 设置关联资源 UUID，调用方负责业务约束。 */
    public void setResourceId(String value) {
        this.resourceId = value;
    }

    /** 返回不含秘密的回执。 */
    public String getReceiptJson() {
        return receiptJson;
    }

    /** 设置不含秘密的回执，调用方负责业务约束。 */
    public void setReceiptJson(String value) {
        this.receiptJson = value;
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
