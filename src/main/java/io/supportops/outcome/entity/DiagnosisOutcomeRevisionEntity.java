package io.supportops.outcome.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 不可变处理修订；只承担持久化映射。 */
@TableName("diagnosis_outcome_revisions")
public class DiagnosisOutcomeRevisionEntity {
    /** 记录主键；UUID由服务端生成，审计为递增游标。 */
    @TableId(type = IdType.INPUT)
    private String id;
    /** 关联处理结果UUID。 */
    private String outcomeId;
    /** 不可变修订号，从1开始。 */
    private Integer revisionNumber;
    /** 有界类型化处理事实及证据JSON。 */
    private String contentJson;
    /** 小写规范JSON SHA-256。 */
    private String contentSha256;
    /** 服务端认证身份UUID。 */
    private String actorId;
    /** UTC ISO 8601人员确认时间。 */
    private String confirmedAt;

    /** 读取记录主键；UUID由服务端生成，审计为递增游标。 */
    public String getId() {
        return id;
    }

    /** 设置记录主键；UUID由服务端生成，审计为递增游标。 */
    public void setId(String value) {
        this.id = value;
    }

    /** 读取关联处理结果UUID。 */
    public String getOutcomeId() {
        return outcomeId;
    }

    /** 设置关联处理结果UUID。 */
    public void setOutcomeId(String value) {
        this.outcomeId = value;
    }

    /** 读取不可变修订号，从1开始。 */
    public Integer getRevisionNumber() {
        return revisionNumber;
    }

    /** 设置不可变修订号，从1开始。 */
    public void setRevisionNumber(Integer value) {
        this.revisionNumber = value;
    }

    /** 读取有界类型化处理事实及证据JSON。 */
    public String getContentJson() {
        return contentJson;
    }

    /** 设置有界类型化处理事实及证据JSON。 */
    public void setContentJson(String value) {
        this.contentJson = value;
    }

    /** 读取小写规范JSON SHA-256。 */
    public String getContentSha256() {
        return contentSha256;
    }

    /** 设置小写规范JSON SHA-256。 */
    public void setContentSha256(String value) {
        this.contentSha256 = value;
    }

    /** 读取服务端认证身份UUID。 */
    public String getActorId() {
        return actorId;
    }

    /** 设置服务端认证身份UUID。 */
    public void setActorId(String value) {
        this.actorId = value;
    }

    /** 读取UTC ISO 8601人员确认时间。 */
    public String getConfirmedAt() {
        return confirmedAt;
    }

    /** 设置UTC ISO 8601人员确认时间。 */
    public void setConfirmedAt(String value) {
        this.confirmedAt = value;
    }
}
