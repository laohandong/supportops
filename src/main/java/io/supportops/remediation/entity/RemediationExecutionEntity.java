package io.supportops.remediation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 修复Execution持久化记录；不作为接口模型。 */
@TableName("remediation_executions")
public class RemediationExecutionEntity {
    /** 执行 UUID。 */
    @TableId(type = IdType.INPUT)
    private String id;
    /** 唯一提案。 */
    private String planId;
    /** 固定目标。 */
    private String targetId;
    /** 适配器操作 UUID。 */
    private String operationId;
    /** 固定业务请求 UUID。 */
    private String verificationRequestId;
    /** 执行实例。 */
    private String instanceId;
    /** 所属进程 PID。 */
    private Long processId;
    /** 进程启动时间；空表示无法确认。 */
    private String processStartedAt;
    /** 执行人。 */
    private String actorId;
    /** 执行综合状态。 */
    private String status;
    /** 配置应用事实。 */
    private String applicationStatus;
    /** 验证事实。 */
    private String verificationStatus;
    /** 完整原操作证据；空为缺失。 */
    private String receiptJson;
    /** 受控错误码。 */
    private String errorCode;
    /** UTC 受理时间。 */
    private String createdAt;
    /** UTC 收尾时间；在途为空。 */
    private String finishedAt;

    /** 返回执行 UUID。 */
    public String getId() {
        return id;
    }

    /** 设置执行 UUID。 */
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

    /** 返回固定目标。 */
    public String getTargetId() {
        return targetId;
    }

    /** 设置固定目标。 */
    public void setTargetId(String value) {
        this.targetId = value;
    }

    /** 返回适配器操作 UUID。 */
    public String getOperationId() {
        return operationId;
    }

    /** 设置适配器操作 UUID。 */
    public void setOperationId(String value) {
        this.operationId = value;
    }

    /** 返回固定业务请求 UUID。 */
    public String getVerificationRequestId() {
        return verificationRequestId;
    }

    /** 设置固定业务请求 UUID。 */
    public void setVerificationRequestId(String value) {
        this.verificationRequestId = value;
    }

    /** 返回执行实例。 */
    public String getInstanceId() {
        return instanceId;
    }

    /** 设置执行实例。 */
    public void setInstanceId(String value) {
        this.instanceId = value;
    }

    /** 返回所属进程 PID。 */
    public Long getProcessId() {
        return processId;
    }

    /** 设置所属进程 PID。 */
    public void setProcessId(Long value) {
        this.processId = value;
    }

    /** 返回进程启动时间；空表示无法确认。 */
    public String getProcessStartedAt() {
        return processStartedAt;
    }

    /** 设置进程启动时间；空表示无法确认。 */
    public void setProcessStartedAt(String value) {
        this.processStartedAt = value;
    }

    /** 返回执行人。 */
    public String getActorId() {
        return actorId;
    }

    /** 设置执行人。 */
    public void setActorId(String value) {
        this.actorId = value;
    }

    /** 返回执行综合状态。 */
    public String getStatus() {
        return status;
    }

    /** 设置执行综合状态。 */
    public void setStatus(String value) {
        this.status = value;
    }

    /** 返回配置应用事实。 */
    public String getApplicationStatus() {
        return applicationStatus;
    }

    /** 设置配置应用事实。 */
    public void setApplicationStatus(String value) {
        this.applicationStatus = value;
    }

    /** 返回验证事实。 */
    public String getVerificationStatus() {
        return verificationStatus;
    }

    /** 设置验证事实。 */
    public void setVerificationStatus(String value) {
        this.verificationStatus = value;
    }

    /** 返回完整原操作证据；空为缺失。 */
    public String getReceiptJson() {
        return receiptJson;
    }

    /** 设置完整原操作证据；空为缺失。 */
    public void setReceiptJson(String value) {
        this.receiptJson = value;
    }

    /** 返回受控错误码。 */
    public String getErrorCode() {
        return errorCode;
    }

    /** 设置受控错误码。 */
    public void setErrorCode(String value) {
        this.errorCode = value;
    }

    /** 返回UTC 受理时间。 */
    public String getCreatedAt() {
        return createdAt;
    }

    /** 设置UTC 受理时间。 */
    public void setCreatedAt(String value) {
        this.createdAt = value;
    }

    /** 返回UTC 收尾时间；在途为空。 */
    public String getFinishedAt() {
        return finishedAt;
    }

    /** 设置UTC 收尾时间；在途为空。 */
    public void setFinishedAt(String value) {
        this.finishedAt = value;
    }
}
