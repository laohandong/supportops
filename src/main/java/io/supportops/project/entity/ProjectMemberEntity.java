package io.supportops.project.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 项目成员的持久化映射，不直接作为接口响应。 */
@TableName("project_members")
public class ProjectMemberEntity {
    /** 资源主键。 */
    @TableId(value = "id", type = IdType.INPUT)
    private String id;
    /** 所属项目 UUID。 */
    private String projectId;
    /** 工作台用户 UUID。 */
    private String userId;
    /** 条件更新版本。 */
    private Integer version;

    /** 创建空映射对象，由业务服务显式校验并设置字段。 */
    public ProjectMemberEntity() {}

    /** 返回资源主键。 */
    public String getId() {
        return id;
    }

    /** 设置资源主键，调用方负责业务约束。 */
    public void setId(String value) {
        this.id = value;
    }

    /** 返回所属项目 UUID。 */
    public String getProjectId() {
        return projectId;
    }

    /** 设置所属项目 UUID，调用方负责业务约束。 */
    public void setProjectId(String value) {
        this.projectId = value;
    }

    /** 返回工作台用户 UUID。 */
    public String getUserId() {
        return userId;
    }

    /** 设置工作台用户 UUID，调用方负责业务约束。 */
    public void setUserId(String value) {
        this.userId = value;
    }

    /** 返回条件更新版本。 */
    public Integer getVersion() {
        return version;
    }

    /** 设置条件更新版本，调用方负责业务约束。 */
    public void setVersion(Integer value) {
        this.version = value;
    }
}
