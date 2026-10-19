package io.supportops.integration.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 固定诊断请求的持久化映射，不直接作为接口响应。 */
@TableName("integration_requests")
public class IntegrationRequestEntity {
    /** 可选外部事实引用的固定结构JSON，不含秘密或可执行动作。 */
    private String externalReferenceJson;
    /** 返回外部事实引用。 */
    public String getExternalReferenceJson() { return externalReferenceJson; }
    /** 设置外部事实引用。 */
    public void setExternalReferenceJson(String value) { externalReferenceJson = value; }
    /** 资源主键。 */
    @TableId(value = "id", type = IdType.INPUT)
    private String id;
    /** 应用 UUID。 */
    private String applicationId;
    /** 固定请求 UUID。 */
    private String requestId;
    /** 摘要协议版本。 */
    private Integer schemaVersion;
    /** 请求SHA256。 */
    private String payloadHash;
    /** 唯一任务 UUID。 */
    private String runId;
    /** UTC受理时间。 */
    private String acceptedAt;

    /** 创建空映射对象，由业务服务显式校验并设置字段。 */
    public IntegrationRequestEntity() {}

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

    /** 返回固定请求 UUID。 */
    public String getRequestId() {
        return requestId;
    }

    /** 设置固定请求 UUID，调用方负责业务约束。 */
    public void setRequestId(String value) {
        this.requestId = value;
    }

    /** 返回摘要协议版本。 */
    public Integer getSchemaVersion() {
        return schemaVersion;
    }

    /** 设置摘要协议版本，调用方负责业务约束。 */
    public void setSchemaVersion(Integer value) {
        this.schemaVersion = value;
    }

    /** 返回请求SHA256。 */
    public String getPayloadHash() {
        return payloadHash;
    }

    /** 设置请求SHA256，调用方负责业务约束。 */
    public void setPayloadHash(String value) {
        this.payloadHash = value;
    }

    /** 返回唯一任务 UUID。 */
    public String getRunId() {
        return runId;
    }

    /** 设置唯一任务 UUID，调用方负责业务约束。 */
    public void setRunId(String value) {
        this.runId = value;
    }

    /** 返回UTC受理时间。 */
    public String getAcceptedAt() {
        return acceptedAt;
    }

    /** 设置UTC受理时间，调用方负责业务约束。 */
    public void setAcceptedAt(String value) {
        this.acceptedAt = value;
    }
}
