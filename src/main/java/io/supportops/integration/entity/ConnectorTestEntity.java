package io.supportops.integration.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 连接分项测试的持久化映射，不直接作为接口响应。 */
@TableName("connector_tests")
public class ConnectorTestEntity {
    /** 资源主键。 */
    @TableId(value = "id", type = IdType.INPUT)
    private String id;
    /** 受测修订 UUID。 */
    private String revisionId;
    /** 明确资源状态。 */
    private String status;
    /** 安全分项回执。 */
    private String resultJson;
    /** UTC开始时间。 */
    private String startedAt;
    /** UTC结束时间。 */
    private String finishedAt;

    /** 创建空映射对象，由业务服务显式校验并设置字段。 */
    public ConnectorTestEntity() {}

    /** 返回资源主键。 */
    public String getId() {
        return id;
    }

    /** 设置资源主键，调用方负责业务约束。 */
    public void setId(String value) {
        this.id = value;
    }

    /** 返回受测修订 UUID。 */
    public String getRevisionId() {
        return revisionId;
    }

    /** 设置受测修订 UUID，调用方负责业务约束。 */
    public void setRevisionId(String value) {
        this.revisionId = value;
    }

    /** 返回明确资源状态。 */
    public String getStatus() {
        return status;
    }

    /** 设置明确资源状态，调用方负责业务约束。 */
    public void setStatus(String value) {
        this.status = value;
    }

    /** 返回安全分项回执。 */
    public String getResultJson() {
        return resultJson;
    }

    /** 设置安全分项回执，调用方负责业务约束。 */
    public void setResultJson(String value) {
        this.resultJson = value;
    }

    /** 返回UTC开始时间。 */
    public String getStartedAt() {
        return startedAt;
    }

    /** 设置UTC开始时间，调用方负责业务约束。 */
    public void setStartedAt(String value) {
        this.startedAt = value;
    }

    /** 返回UTC结束时间。 */
    public String getFinishedAt() {
        return finishedAt;
    }

    /** 设置UTC结束时间，调用方负责业务约束。 */
    public void setFinishedAt(String value) {
        this.finishedAt = value;
    }
}
