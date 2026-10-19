package io.supportops.project.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 诊断环境的持久化映射，不直接作为接口响应。 */
@TableName("system_environments")
public class SystemEnvironmentEntity {
    /** 资源主键。 */
    @TableId(value = "id", type = IdType.INPUT)
    private String id;
    /** 所属系统 UUID。 */
    private String systemId;
    /** 所属范围内唯一编码。 */
    private String code;
    /** 界面名称。 */
    private String name;
    /** 预期版本，最多30字符。 */
    private String productVersion;
    /** 明确资源状态。 */
    private String status;
    /** 启用修订 UUID，未启用为空。 */
    private String currentConnectorRevisionId;
    /** 条件更新版本。 */
    private Integer version;

    /** 创建空映射对象，由业务服务显式校验并设置字段。 */
    public SystemEnvironmentEntity() {}

    /** 返回资源主键。 */
    public String getId() {
        return id;
    }

    /** 设置资源主键，调用方负责业务约束。 */
    public void setId(String value) {
        this.id = value;
    }

    /** 返回所属系统 UUID。 */
    public String getSystemId() {
        return systemId;
    }

    /** 设置所属系统 UUID，调用方负责业务约束。 */
    public void setSystemId(String value) {
        this.systemId = value;
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

    /** 返回预期版本，最多30字符。 */
    public String getProductVersion() {
        return productVersion;
    }

    /** 设置预期版本，最多30字符，调用方负责业务约束。 */
    public void setProductVersion(String value) {
        this.productVersion = value;
    }

    /** 返回明确资源状态。 */
    public String getStatus() {
        return status;
    }

    /** 设置明确资源状态，调用方负责业务约束。 */
    public void setStatus(String value) {
        this.status = value;
    }

    /** 返回启用修订 UUID，未启用为空。 */
    public String getCurrentConnectorRevisionId() {
        return currentConnectorRevisionId;
    }

    /** 设置启用修订 UUID，未启用为空，调用方负责业务约束。 */
    public void setCurrentConnectorRevisionId(String value) {
        this.currentConnectorRevisionId = value;
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
