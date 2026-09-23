package io.supportops.feishu.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 飞书身份绑定与一次性绑定码摘要；绑定码原文只返回管理员一次。 */
@TableName("feishu_bindings")
public class FeishuBindingEntity {
    /** 绑定记录 UUID。 */
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    /** 飞书应用编号。 */
    private String appId;

    /** 允许接入的企业编号。 */
    private String tenantKey;

    /** 关联工作台用户 UUID。 */
    private String userId;

    /** 应用内用户编号；未绑定或撤销时为空。 */
    private String openId;

    /** 一次性绑定码 SHA-256 摘要；消费后为空。 */
    private String codeHash;

    /** 绑定码到期时间，Unix 毫秒。 */
    private Long codeExpiresAt;

    /** 是否已撤销，撤销后停止该绑定的结果投递。 */
    private Boolean revoked;

    /** 创建时间，UTC ISO 8601。 */
    private String createdAt;

    /** 返回绑定记录 UUID。 */
    public String getId() {
        return id;
    }

    /** 设置绑定记录 UUID。 */
    public void setId(String id) {
        this.id = id;
    }

    /** 返回飞书应用编号。 */
    public String getAppId() {
        return appId;
    }

    /** 设置飞书应用编号。 */
    public void setAppId(String appId) {
        this.appId = appId;
    }

    /** 返回允许接入的企业编号。 */
    public String getTenantKey() {
        return tenantKey;
    }

    /** 设置允许接入的企业编号。 */
    public void setTenantKey(String tenantKey) {
        this.tenantKey = tenantKey;
    }

    /** 返回关联工作台用户 UUID。 */
    public String getUserId() {
        return userId;
    }

    /** 设置关联工作台用户 UUID。 */
    public void setUserId(String userId) {
        this.userId = userId;
    }

    /** 返回应用内用户编号；未绑定或撤销时为空。 */
    public String getOpenId() {
        return openId;
    }

    /** 设置应用内用户编号；未绑定或撤销时为空。 */
    public void setOpenId(String openId) {
        this.openId = openId;
    }

    /** 返回一次性绑定码 SHA-256 摘要；消费后为空。 */
    public String getCodeHash() {
        return codeHash;
    }

    /** 设置一次性绑定码 SHA-256 摘要；消费后为空。 */
    public void setCodeHash(String codeHash) {
        this.codeHash = codeHash;
    }

    /** 返回绑定码到期时间，Unix 毫秒。 */
    public Long getCodeExpiresAt() {
        return codeExpiresAt;
    }

    /** 设置绑定码到期时间，Unix 毫秒。 */
    public void setCodeExpiresAt(Long codeExpiresAt) {
        this.codeExpiresAt = codeExpiresAt;
    }

    /** 返回是否已撤销，撤销后停止该绑定的结果投递。 */
    public Boolean getRevoked() {
        return revoked;
    }

    /** 设置是否已撤销，撤销后停止该绑定的结果投递。 */
    public void setRevoked(Boolean revoked) {
        this.revoked = revoked;
    }

    /** 返回创建时间，UTC ISO 8601。 */
    public String getCreatedAt() {
        return createdAt;
    }

    /** 设置创建时间，UTC ISO 8601。 */
    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

}
