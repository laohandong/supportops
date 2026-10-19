package io.supportops.remediation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 修复Plan持久化记录；不作为接口模型。 */
@TableName("remediation_plans")
public class RemediationPlanEntity {
    /** 提案 UUID。 */
    @TableId(type = IdType.INPUT)
    private String id;
    /** 提案名称。 */
    private String name;
    /** 审批生命周期。 */
    private String status;
    /** 乐观锁版本。 */
    private Integer version;
    /** 冻结内容 SHA256。 */
    private String planHash;
    /** 不可变提案 JSON。 */
    private String snapshotJson;
    /** 服务端创建人。 */
    private String creatorId;
    /** 可选来源诊断。 */
    private String sourceRunId;
    /** 固定目标。 */
    private String targetId;
    /** 所属实例。 */
    private String instanceId;
    /** UTC 创建时间。 */
    private String createdAt;
    /** 截止 Unix 毫秒。 */
    private Long expiresAt;

    /** 返回提案 UUID。 */
    public String getId() {
        return id;
    }

    /** 设置提案 UUID。 */
    public void setId(String value) {
        this.id = value;
    }

    /** 返回提案名称。 */
    public String getName() {
        return name;
    }

    /** 设置提案名称。 */
    public void setName(String value) {
        this.name = value;
    }

    /** 返回审批生命周期。 */
    public String getStatus() {
        return status;
    }

    /** 设置审批生命周期。 */
    public void setStatus(String value) {
        this.status = value;
    }

    /** 返回乐观锁版本。 */
    public Integer getVersion() {
        return version;
    }

    /** 设置乐观锁版本。 */
    public void setVersion(Integer value) {
        this.version = value;
    }

    /** 返回冻结内容 SHA256。 */
    public String getPlanHash() {
        return planHash;
    }

    /** 设置冻结内容 SHA256。 */
    public void setPlanHash(String value) {
        this.planHash = value;
    }

    /** 返回不可变提案 JSON。 */
    public String getSnapshotJson() {
        return snapshotJson;
    }

    /** 设置不可变提案 JSON。 */
    public void setSnapshotJson(String value) {
        this.snapshotJson = value;
    }

    /** 返回服务端创建人。 */
    public String getCreatorId() {
        return creatorId;
    }

    /** 设置服务端创建人。 */
    public void setCreatorId(String value) {
        this.creatorId = value;
    }

    /** 返回可选来源诊断。 */
    public String getSourceRunId() {
        return sourceRunId;
    }

    /** 设置可选来源诊断。 */
    public void setSourceRunId(String value) {
        this.sourceRunId = value;
    }

    /** 返回固定目标。 */
    public String getTargetId() {
        return targetId;
    }

    /** 设置固定目标。 */
    public void setTargetId(String value) {
        this.targetId = value;
    }

    /** 返回所属实例。 */
    public String getInstanceId() {
        return instanceId;
    }

    /** 设置所属实例。 */
    public void setInstanceId(String value) {
        this.instanceId = value;
    }

    /** 返回UTC 创建时间。 */
    public String getCreatedAt() {
        return createdAt;
    }

    /** 设置UTC 创建时间。 */
    public void setCreatedAt(String value) {
        this.createdAt = value;
    }

    /** 返回截止 Unix 毫秒。 */
    public Long getExpiresAt() {
        return expiresAt;
    }

    /** 设置截止 Unix 毫秒。 */
    public void setExpiresAt(Long value) {
        this.expiresAt = value;
    }
}
