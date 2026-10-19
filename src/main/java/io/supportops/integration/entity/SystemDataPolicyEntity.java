package io.supportops.integration.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 系统资料的独立外发授权，不由模型或文档内容改变。 */
@TableName("system_data_policies")
public class SystemDataPolicyEntity {
    /** 系统 UUID，也是策略主键。 */
    @TableId(value = "system_id", type = IdType.INPUT) private String systemId;
    /** 允许系统资料及约束进入对话模型。 */
    private Boolean chatAllowed;
    /** 允许文档及检索问题发送向量服务。 */
    private Boolean embeddingAllowed;
    /** 条件更新号，初次保存为1。 */
    private Integer version;
    /** 最近批准时间 ISO-8601。 */
    private String updatedAt;
    /** 创建 MyBatis 映射对象。 */
    public SystemDataPolicyEntity() {}
    /** 返回所属系统。 */
    public String getSystemId() { return systemId; }
    /** 设置所属系统。 */
    public void setSystemId(String value) { systemId = value; }
    /** 返回对话外发批准。 */
    public Boolean getChatAllowed() { return chatAllowed; }
    /** 设置对话外发批准。 */
    public void setChatAllowed(Boolean value) { chatAllowed = value; }
    /** 返回向量外发批准。 */
    public Boolean getEmbeddingAllowed() { return embeddingAllowed; }
    /** 设置向量外发批准。 */
    public void setEmbeddingAllowed(Boolean value) { embeddingAllowed = value; }
    /** 返回更新号。 */
    public Integer getVersion() { return version; }
    /** 设置更新号。 */
    public void setVersion(Integer value) { version = value; }
    /** 返回批准时间。 */
    public String getUpdatedAt() { return updatedAt; }
    /** 设置批准时间。 */
    public void setUpdatedAt(String value) { updatedAt = value; }
    /** 人工批准或签发所属恢复代次。 */
    private Integer approvedGeneration;
    /** 返回批准所属恢复代次。 */
    public Integer getApprovedGeneration() { return approvedGeneration; }
    /** 设置批准所属恢复代次。 */
    public void setApprovedGeneration(Integer value) { approvedGeneration = value; }
}
