package io.supportops.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 每次后台执行的不可变历史和结束结果。 */
@TableName("knowledge_attempts")
public class TaskAttemptEntity {
    /** 尝试 UUID。 */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 任务 UUID。 */
    private String taskId;

    /** 从 1 开始的尝试序号。 */
    private Integer attemptNumber;

    /** 本次执行令牌。 */
    private String leaseOwner;

    /** RUNNING 或尝试结束状态。 */
    private String status;

    /** 最后处理阶段。 */
    private String stage;

    /** 脱敏错误码。 */
    private String errorCode;

    /** 开始时间。 */
    private String startedAt;

    /** 结束时间。 */
    private String finishedAt;

    /** 创建供 Mapper 使用的实体。 */
    public TaskAttemptEntity() {}

    /** 返回尝试 UUID。 */
    public String getId() {
        return id;
    }

    /** 设置尝试 UUID。 */
    public void setId(String id) {
        this.id = id;
    }

    /** 返回任务 UUID。 */
    public String getTaskId() {
        return taskId;
    }

    /** 设置任务 UUID。 */
    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    /** 返回从 1 开始的尝试序号。 */
    public Integer getAttemptNumber() {
        return attemptNumber;
    }

    /** 设置从 1 开始的尝试序号。 */
    public void setAttemptNumber(Integer attemptNumber) {
        this.attemptNumber = attemptNumber;
    }

    /** 返回本次执行令牌。 */
    public String getLeaseOwner() {
        return leaseOwner;
    }

    /** 设置本次执行令牌。 */
    public void setLeaseOwner(String leaseOwner) {
        this.leaseOwner = leaseOwner;
    }

    /** 返回RUNNING 或尝试结束状态。 */
    public String getStatus() {
        return status;
    }

    /** 设置RUNNING 或尝试结束状态。 */
    public void setStatus(String status) {
        this.status = status;
    }

    /** 返回最后处理阶段。 */
    public String getStage() {
        return stage;
    }

    /** 设置最后处理阶段。 */
    public void setStage(String stage) {
        this.stage = stage;
    }

    /** 返回脱敏错误码。 */
    public String getErrorCode() {
        return errorCode;
    }

    /** 设置脱敏错误码。 */
    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    /** 返回开始时间。 */
    public String getStartedAt() {
        return startedAt;
    }

    /** 设置开始时间。 */
    public void setStartedAt(String startedAt) {
        this.startedAt = startedAt;
    }

    /** 返回结束时间。 */
    public String getFinishedAt() {
        return finishedAt;
    }

    /** 设置结束时间。 */
    public void setFinishedAt(String finishedAt) {
        this.finishedAt = finishedAt;
    }
}
