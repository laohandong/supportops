package io.supportops.integration.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 首次初始化状态的持久化映射，不直接作为接口响应。 */
@TableName("deployment_setup_state")
public class DeploymentSetupStateEntity {
    /** 资源主键。 */
    @TableId(value = "id", type = IdType.INPUT)
    private Integer id;
    /** 初始化秘密摘要。 */
    private String setupSecretHash;
    /** UTC消费时间，可为空。 */
    private String consumedAt;
    /** 首次管理员 UUID，可为空。 */
    private String administratorId;

    /** 创建空映射对象，由业务服务显式校验并设置字段。 */
    public DeploymentSetupStateEntity() {}

    /** 返回资源主键。 */
    public Integer getId() {
        return id;
    }

    /** 设置资源主键，调用方负责业务约束。 */
    public void setId(Integer value) {
        this.id = value;
    }

    /** 返回初始化秘密摘要。 */
    public String getSetupSecretHash() {
        return setupSecretHash;
    }

    /** 设置初始化秘密摘要，调用方负责业务约束。 */
    public void setSetupSecretHash(String value) {
        this.setupSecretHash = value;
    }

    /** 返回UTC消费时间，可为空。 */
    public String getConsumedAt() {
        return consumedAt;
    }

    /** 设置UTC消费时间，可为空，调用方负责业务约束。 */
    public void setConsumedAt(String value) {
        this.consumedAt = value;
    }

    /** 返回首次管理员 UUID，可为空。 */
    public String getAdministratorId() {
        return administratorId;
    }

    /** 设置首次管理员 UUID，可为空，调用方负责业务约束。 */
    public void setAdministratorId(String value) {
        this.administratorId = value;
    }
}
