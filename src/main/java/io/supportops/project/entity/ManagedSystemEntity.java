package io.supportops.project.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 被诊断系统的持久化映射，不直接作为接口响应。 */
@TableName("managed_systems")
public class ManagedSystemEntity {
    /** 资源主键。 */
    @TableId(value = "id", type = IdType.INPUT)
    private String id;
    /** 所属项目 UUID。 */
    private String projectId;
    /** 所属范围内唯一编码。 */
    private String code;
    /** 界面名称。 */
    private String name;
    /** 稳定产品标识。 */
    private String productKey;
    /** 明确资源状态。 */
    private String status;
    /** 条件更新版本。 */
    private Integer version;

    /** 创建空映射对象，由业务服务显式校验并设置字段。 */
    public ManagedSystemEntity() {}

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

    /** 返回稳定产品标识。 */
    public String getProductKey() {
        return productKey;
    }

    /** 设置稳定产品标识，调用方负责业务约束。 */
    public void setProductKey(String value) {
        this.productKey = value;
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
}
