package io.supportops.integration.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 应用环境授权的持久化映射，不直接作为接口响应。 */
@TableName("application_environment_grants")
public class ApplicationEnvironmentGrantEntity {
    /** 资源主键。 */
    @TableId(value = "id", type = IdType.INPUT)
    private String id;
    /** 应用 UUID。 */
    private String applicationId;
    /** 目标环境 UUID。 */
    private String environmentId;

    /** 创建空映射对象，由业务服务显式校验并设置字段。 */
    public ApplicationEnvironmentGrantEntity() {}

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

    /** 返回目标环境 UUID。 */
    public String getEnvironmentId() {
        return environmentId;
    }

    /** 设置目标环境 UUID，调用方负责业务约束。 */
    public void setEnvironmentId(String value) {
        this.environmentId = value;
    }
}
