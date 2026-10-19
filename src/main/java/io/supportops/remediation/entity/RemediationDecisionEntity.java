package io.supportops.remediation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 修复Decision持久化记录；不作为接口模型。 */
@TableName("remediation_decisions")
public class RemediationDecisionEntity {
    /** 决策 UUID。 */
    @TableId(type = IdType.INPUT)
    private String id;
    /** 唯一提案。 */
    private String planId;
    /** 批准内容摘要。 */
    private String planHash;
    /** APPROVE 或 REJECT。 */
    private String kind;
    /** 服务端审批人。 */
    private String actorId;
    /** 审批理由。 */
    private String reason;
    /** 批准截止 Unix 毫秒。 */
    private Long expiresAt;
    /** UTC 决策时间。 */
    private String createdAt;

    /** 返回决策 UUID。 */
    public String getId() {
        return id;
    }

    /** 设置决策 UUID。 */
    public void setId(String value) {
        this.id = value;
    }

    /** 返回唯一提案。 */
    public String getPlanId() {
        return planId;
    }

    /** 设置唯一提案。 */
    public void setPlanId(String value) {
        this.planId = value;
    }

    /** 返回批准内容摘要。 */
    public String getPlanHash() {
        return planHash;
    }

    /** 设置批准内容摘要。 */
    public void setPlanHash(String value) {
        this.planHash = value;
    }

    /** 返回APPROVE 或 REJECT。 */
    public String getKind() {
        return kind;
    }

    /** 设置APPROVE 或 REJECT。 */
    public void setKind(String value) {
        this.kind = value;
    }

    /** 返回服务端审批人。 */
    public String getActorId() {
        return actorId;
    }

    /** 设置服务端审批人。 */
    public void setActorId(String value) {
        this.actorId = value;
    }

    /** 返回审批理由。 */
    public String getReason() {
        return reason;
    }

    /** 设置审批理由。 */
    public void setReason(String value) {
        this.reason = value;
    }

    /** 返回批准截止 Unix 毫秒。 */
    public Long getExpiresAt() {
        return expiresAt;
    }

    /** 设置批准截止 Unix 毫秒。 */
    public void setExpiresAt(Long value) {
        this.expiresAt = value;
    }

    /** 返回UTC 决策时间。 */
    public String getCreatedAt() {
        return createdAt;
    }

    /** 设置UTC 决策时间。 */
    public void setCreatedAt(String value) {
        this.createdAt = value;
    }
}
