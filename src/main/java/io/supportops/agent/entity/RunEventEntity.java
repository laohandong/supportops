package io.supportops.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 诊断执行事件，自增主键作为增量读取游标。 */
@TableName("run_events")
public class RunEventEntity {
    /** 数据库生成的事件游标。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所属诊断任务 UUID。 */
    @TableField("run_id")
    private String runId;

    /** 事件种类。 */
    @TableField("kind")
    private String kind;

    /** 事件载荷 JSON，不保存模型内部思维过程。 */
    @TableField("content")
    private String content;

    /** 事件写入时间，ISO 8601 字符串。 */
    @TableField("created_at")
    private String createdAt;

    /** 创建供 MyBatis 使用的空实体。 */
    public RunEventEntity() {}

    /** 使用明确字段创建实体，调用方负责业务校验。 */
    public RunEventEntity(Long id, String runId, String kind, String content, String createdAt) {
        this.id = id;
        this.runId = runId;
        this.kind = kind;
        this.content = content;
        this.createdAt = createdAt;
    }

    /** 返回数据库生成的事件游标。 */
    public Long getId() {
        return id;
    }

    /** 设置数据库生成的事件游标。 */
    public void setId(Long id) {
        this.id = id;
    }

    /** 返回所属诊断任务 UUID。 */
    public String getRunId() {
        return runId;
    }

    /** 设置所属诊断任务 UUID。 */
    public void setRunId(String runId) {
        this.runId = runId;
    }

    /** 返回事件种类。 */
    public String getKind() {
        return kind;
    }

    /** 设置事件种类。 */
    public void setKind(String kind) {
        this.kind = kind;
    }

    /** 返回事件载荷 JSON，不保存模型内部思维过程。 */
    public String getContent() {
        return content;
    }

    /** 设置事件载荷 JSON，不保存模型内部思维过程。 */
    public void setContent(String content) {
        this.content = content;
    }

    /** 返回事件写入时间，ISO 8601 字符串。 */
    public String getCreatedAt() {
        return createdAt;
    }

    /** 设置事件写入时间，ISO 8601 字符串。 */
    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }
}
