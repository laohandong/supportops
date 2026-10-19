package io.supportops.project.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 人员环境授权的持久化映射，不直接作为接口响应。 */
@TableName("environment_user_grants")
public class EnvironmentUserGrantEntity {
    /** 资源主键。 */
    @TableId(value = "id", type = IdType.INPUT)
    private String id;
    /** 目标环境 UUID。 */
    private String environmentId;
    /** 工作台用户 UUID。 */
    private String userId;
    /** 条件更新版本。 */
    private Integer version;

    /** 创建空映射对象，由业务服务显式校验并设置字段。 */
    public EnvironmentUserGrantEntity() {}

    /** 返回资源主键。 */
    public String getId() {
        return id;
    }

    /** 设置资源主键，调用方负责业务约束。 */
    public void setId(String value) {
        this.id = value;
    }

    /** 返回目标环境 UUID。 */
    public String getEnvironmentId() {
        return environmentId;
    }

    /** 设置目标环境 UUID，调用方负责业务约束。 */
    public void setEnvironmentId(String value) {
        this.environmentId = value;
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
