package io.supportops.outcome.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** outcome_export_snapshots 的持久化映射；不作为 HTTP 响应。 */
@TableName("outcome_export_snapshots")
public class OutcomeExportEntity {
    /** 导出包UUID。 */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 外部来源诊断UUID，不设置删除级联。 */
    private String sourceRunId;

    /** 确认的来源修订UUID。 */
    private String outcomeRevisionId;

    /** 服务端记录的管理员UUID。 */
    private String actorId;

    /** 不可变白名单来源包。 */
    private String packageJson;

    /** 来源包规范摘要。 */
    private String contentSha256;

    /** UTC导出时间。 */
    private String createdAt;

    /** 读取导出包UUID。 */
    public String getId() {
        return id;
    }

    /** 设置导出包UUID。 */
    public void setId(String value) {
        this.id = value;
    }

    /** 读取外部来源诊断UUID，不设置删除级联。 */
    public String getSourceRunId() {
        return sourceRunId;
    }

    /** 设置外部来源诊断UUID，不设置删除级联。 */
    public void setSourceRunId(String value) {
        this.sourceRunId = value;
    }

    /** 读取确认的来源修订UUID。 */
    public String getOutcomeRevisionId() {
        return outcomeRevisionId;
    }

    /** 设置确认的来源修订UUID。 */
    public void setOutcomeRevisionId(String value) {
        this.outcomeRevisionId = value;
    }

    /** 读取服务端记录的管理员UUID。 */
    public String getActorId() {
        return actorId;
    }

    /** 设置服务端记录的管理员UUID。 */
    public void setActorId(String value) {
        this.actorId = value;
    }

    /** 读取不可变白名单来源包。 */
    public String getPackageJson() {
        return packageJson;
    }

    /** 设置不可变白名单来源包。 */
    public void setPackageJson(String value) {
        this.packageJson = value;
    }

    /** 读取来源包规范摘要。 */
    public String getContentSha256() {
        return contentSha256;
    }

    /** 设置来源包规范摘要。 */
    public void setContentSha256(String value) {
        this.contentSha256 = value;
    }

    /** 读取UTC导出时间。 */
    public String getCreatedAt() {
        return createdAt;
    }

    /** 设置UTC导出时间。 */
    public void setCreatedAt(String value) {
        this.createdAt = value;
    }
}
