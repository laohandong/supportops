package io.supportops.project.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 企业项目的持久化映射，不直接作为接口响应。 */
@TableName("projects")
public class ProjectEntity {
    /** 资源主键。 */
    @TableId(value = "id", type = IdType.INPUT)
    private String id;
    /** 所属范围内唯一编码。 */
    private String code;
    /** 界面名称。 */
    private String name;
    /** 明确资源状态。 */
    private String status;
    /** 条件更新版本。 */
    private Integer version;
    /** UTC ISO 8601 创建时间。 */
    private String createdAt;

    /** 创建空映射对象，由业务服务显式校验并设置字段。 */
    public ProjectEntity() {}

    /** 返回资源主键。 */
    public String getId() {
        return id;
    }

    /** 设置资源主键，调用方负责业务约束。 */
    public void setId(String value) {
        this.id = value;
    }

    /** 返回所属范围内唯一编码。 */
    public String getCode() {
        return code;
    }

    /** 设置所属范围内唯一编码，调用方负责业务约束。 */
    public void setCode(String value) {
        this.code = value;
    }

    /** 返回界面名称。 */
    public String getName() {
        return name;
    }

    /** 设置界面名称，调用方负责业务约束。 */
    public void setName(String value) {
        this.name = value;
    }

    /** 返回明确资源状态。 */
    public String getStatus() {
        return status;
    }

    /** 设置明确资源状态，调用方负责业务约束。 */
    public void setStatus(String value) {
        this.status = value;
    }

    /** 返回条件更新版本。 */
    public Integer getVersion() {
        return version;
    }

    /** 设置条件更新版本，调用方负责业务约束。 */
    public void setVersion(Integer value) {
        this.version = value;
    }

    /** 返回UTC ISO 8601 创建时间。 */
    public String getCreatedAt() {
        return createdAt;
    }

    /** 设置UTC ISO 8601 创建时间，调用方负责业务约束。 */
    public void setCreatedAt(String value) {
        this.createdAt = value;
    }
}
