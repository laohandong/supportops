package io.supportops.remediation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 修复Request持久化记录；不作为接口模型。 */
@TableName("remediation_requests")
public class RemediationRequestEntity {
    /** 内部请求 UUID。 */
    @TableId(type = IdType.INPUT)
    private String id;
    /** 服务端请求人。 */
    private String actorId;
    /** 操作者内唯一固定请求 UUID。 */
    private String requestId;
    /** 命令种类。 */
    private String kind;
    /** 命令资源。 */
    private String targetId;
    /** 规范化正文 SHA256。 */
    private String digest;
    /** 结果关联提案；拒绝可为空。 */
    private String planId;
    /** 结果关联执行；非执行为空。 */
    private String executionId;
    /** 稳定受理或拒绝状态。 */
    private Integer httpStatus;
    /** 持久化拒绝码；成功为空。 */
    private String errorCode;
    /** UTC 受理时间。 */
    private String createdAt;

    /** 返回内部请求 UUID。 */
    public String getId() {
        return id;
    }

    /** 设置内部请求 UUID。 */
    public void setId(String value) {
        this.id = value;
    }

    /** 返回服务端请求人。 */
    public String getActorId() {
        return actorId;
    }

    /** 设置服务端请求人。 */
    public void setActorId(String value) {
        this.actorId = value;
    }

    /** 返回操作者内唯一固定请求 UUID。 */
    public String getRequestId() {
        return requestId;
    }

    /** 设置操作者内唯一固定请求 UUID。 */
    public void setRequestId(String value) {
        this.requestId = value;
    }

    /** 返回命令种类。 */
    public String getKind() {
        return kind;
    }

    /** 设置命令种类。 */
    public void setKind(String value) {
        this.kind = value;
    }

    /** 返回命令资源。 */
    public String getTargetId() {
        return targetId;
    }

    /** 设置命令资源。 */
    public void setTargetId(String value) {
        this.targetId = value;
    }

    /** 返回规范化正文 SHA256。 */
    public String getDigest() {
        return digest;
    }

    /** 设置规范化正文 SHA256。 */
    public void setDigest(String value) {
        this.digest = value;
    }

    /** 返回结果关联提案；拒绝可为空。 */
    public String getPlanId() {
        return planId;
    }

    /** 设置结果关联提案；拒绝可为空。 */
    public void setPlanId(String value) {
        this.planId = value;
    }

    /** 返回结果关联执行；非执行为空。 */
    public String getExecutionId() {
        return executionId;
    }

    /** 设置结果关联执行；非执行为空。 */
    public void setExecutionId(String value) {
        this.executionId = value;
    }

    /** 返回稳定受理或拒绝状态。 */
    public Integer getHttpStatus() {
        return httpStatus;
    }

    /** 设置稳定受理或拒绝状态。 */
    public void setHttpStatus(Integer value) {
        this.httpStatus = value;
    }

    /** 返回持久化拒绝码；成功为空。 */
    public String getErrorCode() {
        return errorCode;
    }

    /** 设置持久化拒绝码；成功为空。 */
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
}
