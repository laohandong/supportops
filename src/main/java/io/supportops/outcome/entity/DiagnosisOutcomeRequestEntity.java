package io.supportops.outcome.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 原命令终局回执；只承担持久化映射。 */
@TableName("diagnosis_outcome_requests")
public class DiagnosisOutcomeRequestEntity {
    /** 记录主键；UUID由服务端生成，审计为递增游标。 */
    @TableId(type = IdType.INPUT)
    private String id;
    /** 服务端认证身份UUID。 */
    private String actorId;
    /** 冻结原请求UUID。 */
    private String requestId;
    /** 受控命令或审计动作。 */
    private String kind;
    /** 固定请求目标。 */
    private String targetId;
    /** 原规范正文摘要，不保留拒绝正文。 */
    private String requestDigest;
    /** 原终局HTTP状态。 */
    private Integer httpStatus;
    /** 受控错误码，成功为空。 */
    private String errorCode;
    /** 原命令产生的资源UUID，拒绝为空。 */
    private String resourceId;
    /** 原命令不可变修订UUID。 */
    private String revisionId;
    /** UTC ISO 8601创建时间。 */
    private String createdAt;

    /** 读取记录主键；UUID由服务端生成，审计为递增游标。 */
    public String getId() {
        return id;
    }

    /** 设置记录主键；UUID由服务端生成，审计为递增游标。 */
    public void setId(String value) {
        this.id = value;
    }

    /** 读取服务端认证身份UUID。 */
    public String getActorId() {
        return actorId;
    }

    /** 设置服务端认证身份UUID。 */
    public void setActorId(String value) {
        this.actorId = value;
    }

    /** 读取冻结原请求UUID。 */
    public String getRequestId() {
        return requestId;
    }

    /** 设置冻结原请求UUID。 */
    public void setRequestId(String value) {
        this.requestId = value;
    }

    /** 读取受控命令或审计动作。 */
    public String getKind() {
        return kind;
    }

    /** 设置受控命令或审计动作。 */
    public void setKind(String value) {
        this.kind = value;
    }

    /** 读取固定请求目标。 */
    public String getTargetId() {
        return targetId;
    }

    /** 设置固定请求目标。 */
    public void setTargetId(String value) {
        this.targetId = value;
    }

    /** 读取原规范正文摘要，不保留拒绝正文。 */
    public String getRequestDigest() {
        return requestDigest;
    }

    /** 设置原规范正文摘要，不保留拒绝正文。 */
    public void setRequestDigest(String value) {
        this.requestDigest = value;
    }

    /** 读取原终局HTTP状态。 */
    public Integer getHttpStatus() {
        return httpStatus;
    }

    /** 设置原终局HTTP状态。 */
    public void setHttpStatus(Integer value) {
        this.httpStatus = value;
    }

    /** 读取受控错误码，成功为空。 */
    public String getErrorCode() {
        return errorCode;
    }

    /** 设置受控错误码，成功为空。 */
    public void setErrorCode(String value) {
        this.errorCode = value;
    }

    /** 读取原命令产生的资源UUID，拒绝为空。 */
    public String getResourceId() {
        return resourceId;
    }

    /** 设置原命令产生的资源UUID，拒绝为空。 */
    public void setResourceId(String value) {
        this.resourceId = value;
    }

    /** 读取原命令不可变修订UUID。 */
    public String getRevisionId() {
        return revisionId;
    }

    /** 设置原命令不可变修订UUID。 */
    public void setRevisionId(String value) {
        this.revisionId = value;
    }

    /** 读取UTC ISO 8601创建时间。 */
    public String getCreatedAt() {
        return createdAt;
    }

    /** 设置UTC ISO 8601创建时间。 */
    public void setCreatedAt(String value) {
        this.createdAt = value;
    }
}
