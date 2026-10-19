package io.supportops.outcome.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 处理结果主记录；只承担持久化映射。 */
@TableName("diagnosis_outcomes")
public class DiagnosisOutcomeEntity {
    /** 记录主键；UUID由服务端生成，审计为递增游标。 */
    @TableId(type = IdType.INPUT)
    private String id;
    /** 来源诊断轮次UUID。 */
    private String runId;
    /** 并发版本，从1开始。 */
    private Integer version;
    /** 当前不可变修订UUID。 */
    private String currentRevisionId;
    /** UTC ISO 8601创建时间。 */
    private String createdAt;
    /** UTC ISO 8601更新时间。 */
    private String updatedAt;

    /** 读取记录主键；UUID由服务端生成，审计为递增游标。 */
    public String getId() {
        return id;
    }

    /** 设置记录主键；UUID由服务端生成，审计为递增游标。 */
    public void setId(String value) {
        this.id = value;
    }

    /** 读取来源诊断轮次UUID。 */
    public String getRunId() {
        return runId;
    }

    /** 设置来源诊断轮次UUID。 */
    public void setRunId(String value) {
        this.runId = value;
    }

    /** 读取并发版本，从1开始。 */
    public Integer getVersion() {
        return version;
    }

    /** 设置并发版本，从1开始。 */
    public void setVersion(Integer value) {
        this.version = value;
    }

    /** 读取当前不可变修订UUID。 */
    public String getCurrentRevisionId() {
        return currentRevisionId;
    }

    /** 设置当前不可变修订UUID。 */
    public void setCurrentRevisionId(String value) {
        this.currentRevisionId = value;
    }

    /** 读取UTC ISO 8601创建时间。 */
    public String getCreatedAt() {
        return createdAt;
    }

    /** 设置UTC ISO 8601创建时间。 */
    public void setCreatedAt(String value) {
        this.createdAt = value;
    }

    /** 读取UTC ISO 8601更新时间。 */
    public String getUpdatedAt() {
        return updatedAt;
    }

    /** 设置UTC ISO 8601更新时间。 */
    public void setUpdatedAt(String value) {
        this.updatedAt = value;
    }
}
