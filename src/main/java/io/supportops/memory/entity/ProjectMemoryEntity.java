package io.supportops.memory.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 人员显式确认的项目约束，不保存模型自动推测的记忆。 */
@TableName("memories")
public class ProjectMemoryEntity {
    /** 项目记忆 UUID。 */
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    /** 已确认的稳定事实或执行约束。 */
    @TableField("content")
    private String content;

    /** 确认时间，ISO 8601 字符串。 */
    @TableField("created_at")
    private String createdAt;

    /** 创建供 MyBatis 使用的空实体。 */
    public ProjectMemoryEntity() {}

    /** 使用明确字段创建实体，调用方负责业务校验。 */
    public ProjectMemoryEntity(String id, String content, String createdAt) {
        this.id = id;
        this.content = content;
        this.createdAt = createdAt;
    }

    /** 返回项目记忆 UUID。 */
    public String getId() {
        return id;
    }

    /** 设置项目记忆 UUID。 */
    public void setId(String id) {
        this.id = id;
    }

    /** 返回已确认的稳定事实或执行约束。 */
    public String getContent() {
        return content;
    }

    /** 设置已确认的稳定事实或执行约束。 */
    public void setContent(String content) {
        this.content = content;
    }

    /** 返回确认时间，ISO 8601 字符串。 */
    public String getCreatedAt() {
        return createdAt;
    }

    /** 设置确认时间，ISO 8601 字符串。 */
    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }
}
