package io.supportops.integration.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 单实例执行保护的持久化映射，不直接作为接口响应。 */
@TableName("deployment_runtime_guard")
public class DeploymentRuntimeGuardEntity {
    /** 资源主键。 */
    @TableId(value = "id", type = IdType.INPUT)
    private Integer id;
    /** 稳定部署标识。 */
    private String deploymentId;
    /** 持有运行 UUID。 */
    private String holderRuntimeId;
    /** 实际主机标识。 */
    private String host;
    /** OS进程编号。 */
    private Long pid;
    /** OS实际启动时间。 */
    private String processStartedAt;
    /** 条件更新版本。 */
    private Integer version;
    /** 恢复代次，历史批准仅在同代次有效。 */
    private Integer restoreGeneration;
    /** 实际运行JAR摘要，开发运行为空。 */
    private String programSha256;

    /** 创建空映射对象，由业务服务显式校验并设置字段。 */
    public DeploymentRuntimeGuardEntity() {}

    /** 返回资源主键。 */
    public Integer getId() {
        return id;
    }

    /** 设置资源主键，调用方负责业务约束。 */
    public void setId(Integer value) {
        this.id = value;
    }

    /** 返回稳定部署标识。 */
    public String getDeploymentId() {
        return deploymentId;
    }

    /** 设置稳定部署标识，调用方负责业务约束。 */
    public void setDeploymentId(String value) {
        this.deploymentId = value;
    }

    /** 返回持有运行 UUID。 */
    public String getHolderRuntimeId() {
        return holderRuntimeId;
    }

    /** 设置持有运行 UUID，调用方负责业务约束。 */
    public void setHolderRuntimeId(String value) {
        this.holderRuntimeId = value;
    }

    /** 返回实际主机标识。 */
    public String getHost() {
        return host;
    }

    /** 设置实际主机标识，调用方负责业务约束。 */
    public void setHost(String value) {
        this.host = value;
    }

    /** 返回OS进程编号。 */
    public Long getPid() {
        return pid;
    }

    /** 设置OS进程编号，调用方负责业务约束。 */
    public void setPid(Long value) {
        this.pid = value;
    }

    /** 返回OS实际启动时间。 */
    public String getProcessStartedAt() {
        return processStartedAt;
    }

    /** 设置OS实际启动时间，调用方负责业务约束。 */
    public void setProcessStartedAt(String value) {
        this.processStartedAt = value;
    }

    /** 返回条件更新版本。 */
    public Integer getVersion() {
        return version;
    }

    /** 设置条件更新版本，调用方负责业务约束。 */
    public void setVersion(Integer value) {
        this.version = value;
    }
    /** 返回恢复代次。 */
    public Integer getRestoreGeneration() { return restoreGeneration; }
    /** 设置恢复代次。 */
    public void setRestoreGeneration(Integer value) { restoreGeneration = value; }
    /** 返回实际程序摘要。 */
    public String getProgramSha256() { return programSha256; }
    /** 设置实际程序摘要。 */
    public void setProgramSha256(String value) { programSha256 = value; }
}
