package io.supportops.integration.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 追加接入审计的持久化映射，不直接作为接口响应。 */
@TableName("integration_audit_events")
public class IntegrationAuditEventEntity {
    /** 资源主键。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    /** USER APPLICATION 或 SYSTEM。 */
    private String actorType;
    /** 主体 UUID，可为空。 */
    private String actorId;
    /** 操作名称。 */
    private String action;
    /** 资源类型。 */
    private String resourceType;
    /** 关联资源 UUID。 */
    private String resourceId;
    /** 固定请求 UUID。 */
    private String requestId;
    /** 无私密正文的审计详情。 */
    private String detailJson;
    /** UTC ISO 8601 创建时间。 */
    private String createdAt;

    /** 创建空映射对象，由业务服务显式校验并设置字段。 */
    public IntegrationAuditEventEntity() {}

    /** 返回资源主键。 */
    public Long getId() {
        return id;
    }

    /** 设置资源主键，调用方负责业务约束。 */
    public void setId(Long value) {
        this.id = value;
    }

    /** 返回USER APPLICATION 或 SYSTEM。 */
    public String getActorType() {
        return actorType;
    }

    /** 设置USER APPLICATION 或 SYSTEM，调用方负责业务约束。 */
    public void setActorType(String value) {
        this.actorType = value;
    }

    /** 返回主体 UUID，可为空。 */
    public String getActorId() {
        return actorId;
    }

    /** 设置主体 UUID，可为空，调用方负责业务约束。 */
    public void setActorId(String value) {
        this.actorId = value;
    }

    /** 返回操作名称。 */
    public String getAction() {
        return action;
    }

    /** 设置操作名称，调用方负责业务约束。 */
    public void setAction(String value) {
        this.action = value;
    }

    /** 返回资源类型。 */
    public String getResourceType() {
        return resourceType;
    }

    /** 设置资源类型，调用方负责业务约束。 */
    public void setResourceType(String value) {
        this.resourceType = value;
    }

    /** 返回关联资源 UUID。 */
    public String getResourceId() {
        return resourceId;
    }

    /** 设置关联资源 UUID，调用方负责业务约束。 */
    public void setResourceId(String value) {
        this.resourceId = value;
    }

    /** 返回固定请求 UUID。 */
    public String getRequestId() {
        return requestId;
    }

    /** 设置固定请求 UUID，调用方负责业务约束。 */
    public void setRequestId(String value) {
        this.requestId = value;
    }

    /** 返回无私密正文的审计详情。 */
    public String getDetailJson() {
        return detailJson;
    }

    /** 设置无私密正文的审计详情，调用方负责业务约束。 */
    public void setDetailJson(String value) {
        this.detailJson = value;
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
