package io.supportops.integration.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 调用应用身份的持久化映射，不直接作为接口响应。 */
@TableName("integration_applications")
public class IntegrationApplicationEntity {
    /** 资源主键。 */
    @TableId(value = "id", type = IdType.INPUT)
    private String id;
    /** 所属项目 UUID。 */
    private String projectId;
    /** 所属范围内唯一编码。 */
    private String code;
    /** 界面名称。 */
    private String name;
    /** 明确资源状态。 */
    private String status;
    /** 固定权限数组。 */
    private String permissionsJson;
    /** 每分钟提交上限。 */
    private Integer submitPerMinute;
    /** 每分钟读取上限。 */
    private Integer readPerMinute;
    /** 未释放任务上限。 */
    private Integer maxActive;
    /** 条件更新版本。 */
    private Integer version;

    /** 创建空映射对象，由业务服务显式校验并设置字段。 */
    public IntegrationApplicationEntity() {}

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

    /** 返回明确资源状态。 */
    public String getStatus() {
        return status;
    }

    /** 设置明确资源状态，调用方负责业务约束。 */
    public void setStatus(String value) {
        this.status = value;
    }

    /** 返回固定权限数组。 */
    public String getPermissionsJson() {
        return permissionsJson;
    }

    /** 设置固定权限数组，调用方负责业务约束。 */
    public void setPermissionsJson(String value) {
        this.permissionsJson = value;
    }

    /** 返回每分钟提交上限。 */
    public Integer getSubmitPerMinute() {
        return submitPerMinute;
    }

    /** 设置每分钟提交上限，调用方负责业务约束。 */
    public void setSubmitPerMinute(Integer value) {
        this.submitPerMinute = value;
    }

    /** 返回每分钟读取上限。 */
    public Integer getReadPerMinute() {
        return readPerMinute;
    }

    /** 设置每分钟读取上限，调用方负责业务约束。 */
    public void setReadPerMinute(Integer value) {
        this.readPerMinute = value;
    }

    /** 返回未释放任务上限。 */
    public Integer getMaxActive() {
        return maxActive;
    }

    /** 设置未释放任务上限，调用方负责业务约束。 */
    public void setMaxActive(Integer value) {
        this.maxActive = value;
    }

    /** 返回条件更新版本。 */
    public Integer getVersion() {
        return version;
    }

    /** 设置条件更新版本，调用方负责业务约束。 */
    public void setVersion(Integer value) {
        this.version = value;
    }
    /** 人工批准或签发所属恢复代次。 */
    private Integer approvedGeneration;
    /** 返回批准所属恢复代次。 */
    public Integer getApprovedGeneration() { return approvedGeneration; }
    /** 设置批准所属恢复代次。 */
    public void setApprovedGeneration(Integer value) { approvedGeneration = value; }
}
