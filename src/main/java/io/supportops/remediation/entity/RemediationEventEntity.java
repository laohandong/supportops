package io.supportops.remediation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 修复Event持久化记录；不作为接口模型。 */
@TableName("remediation_events")
public class RemediationEventEntity {
    /** 全局递增审计游标。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 关联提案。 */
    private String planId;
    /** 关联执行；可空。 */
    private String executionId;
    /** 操作者；启动恢复可空。 */
    private String actorId;
    /** 审计事件类型。 */
    private String kind;
    /** 有界脱敏证据 JSON。 */
    private String payloadJson;
    /** UTC 事件时间。 */
    private String createdAt;

    /** 返回全局递增审计游标。 */
    public Long getId() {
        return id;
    }

    /** 设置全局递增审计游标。 */
    public void setId(Long value) {
        this.id = value;
    }

    /** 返回关联提案。 */
    public String getPlanId() {
        return planId;
    }

    /** 设置关联提案。 */
    public void setPlanId(String value) {
        this.planId = value;
    }

    /** 返回关联执行；可空。 */
    public String getExecutionId() {
        return executionId;
    }

    /** 设置关联执行；可空。 */
    public void setExecutionId(String value) {
        this.executionId = value;
    }

    /** 返回操作者；启动恢复可空。 */
    public String getActorId() {
        return actorId;
    }

    /** 设置操作者；启动恢复可空。 */
    public void setActorId(String value) {
        this.actorId = value;
    }

    /** 返回审计事件类型。 */
    public String getKind() {
        return kind;
    }

    /** 设置审计事件类型。 */
    public void setKind(String value) {
        this.kind = value;
    }

    /** 返回有界脱敏证据 JSON。 */
    public String getPayloadJson() {
        return payloadJson;
    }

    /** 设置有界脱敏证据 JSON。 */
    public void setPayloadJson(String value) {
        this.payloadJson = value;
    }

    /** 返回UTC 事件时间。 */
    public String getCreatedAt() {
        return createdAt;
    }

    /** 设置UTC 事件时间。 */
    public void setCreatedAt(String value) {
        this.createdAt = value;
    }
}
