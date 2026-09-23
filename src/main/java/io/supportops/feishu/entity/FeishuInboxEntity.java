package io.supportops.feishu.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 飞书私聊收件记录；先保存稳定请求编号，再受理诊断。 */
@TableName("feishu_inbox")
public class FeishuInboxEntity {
    /** 请求 UUID，同时作为诊断任务编号；通知记录没有诊断。 */
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    /** 收到消息的飞书应用编号。 */
    private String appId;

    /** 消息所属企业编号。 */
    private String tenantKey;

    /** 飞书消息唯一编号，用于持久化去重。 */
    private String messageId;

    /** 消息发送者的应用内用户编号。 */
    private String openId;

    /** 受理时的绑定记录 UUID；未绑定通知为空。 */
    private String bindingId;

    /** 服务端绑定的工作台用户 UUID；未绑定通知为空。 */
    private String userId;

    /** 诊断问题；绑定命令仅保存空字符串。 */
    private String question;

    /** RECEIVED 待受理、WAITING 等待结果、FINISHED 已生成回复、BLOCKED 绑定失效。 */
    private String state;

    /** 脱敏受理错误码；正常为空字符串。 */
    private String errorCode;

    /** 接收时间，UTC ISO 8601。 */
    private String createdAt;

    /** 返回请求 UUID，同时作为诊断任务编号；通知记录没有诊断。 */
    public String getId() {
        return id;
    }

    /** 设置请求 UUID，同时作为诊断任务编号；通知记录没有诊断。 */
    public void setId(String id) {
        this.id = id;
    }

    /** 返回收到消息的飞书应用编号。 */
    public String getAppId() {
        return appId;
    }

    /** 设置收到消息的飞书应用编号。 */
    public void setAppId(String appId) {
        this.appId = appId;
    }

    /** 返回消息所属企业编号。 */
    public String getTenantKey() {
        return tenantKey;
    }

    /** 设置消息所属企业编号。 */
    public void setTenantKey(String tenantKey) {
        this.tenantKey = tenantKey;
    }

    /** 返回飞书消息唯一编号，用于持久化去重。 */
    public String getMessageId() {
        return messageId;
    }

    /** 设置飞书消息唯一编号，用于持久化去重。 */
    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    /** 返回消息发送者的应用内用户编号。 */
    public String getOpenId() {
        return openId;
    }

    /** 设置消息发送者的应用内用户编号。 */
    public void setOpenId(String openId) {
        this.openId = openId;
    }

    /** 返回受理时的绑定记录 UUID；未绑定通知为空。 */
    public String getBindingId() {
        return bindingId;
    }

    /** 设置受理时的绑定记录 UUID；未绑定通知为空。 */
    public void setBindingId(String bindingId) {
        this.bindingId = bindingId;
    }

    /** 返回服务端绑定的工作台用户 UUID；未绑定通知为空。 */
    public String getUserId() {
        return userId;
    }

    /** 设置服务端绑定的工作台用户 UUID；未绑定通知为空。 */
    public void setUserId(String userId) {
        this.userId = userId;
    }

    /** 返回诊断问题；绑定命令仅保存空字符串。 */
    public String getQuestion() {
        return question;
    }

    /** 设置诊断问题；绑定命令仅保存空字符串。 */
    public void setQuestion(String question) {
        this.question = question;
    }

    /** 返回RECEIVED 待受理、WAITING 等待结果、FINISHED 已生成回复、BLOCKED 绑定失效。 */
    public String getState() {
        return state;
    }

    /** 设置RECEIVED 待受理、WAITING 等待结果、FINISHED 已生成回复、BLOCKED 绑定失效。 */
    public void setState(String state) {
        this.state = state;
    }

    /** 返回脱敏受理错误码；正常为空字符串。 */
    public String getErrorCode() {
        return errorCode;
    }

    /** 设置脱敏受理错误码；正常为空字符串。 */
    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    /** 返回接收时间，UTC ISO 8601。 */
    public String getCreatedAt() {
        return createdAt;
    }

    /** 设置接收时间，UTC ISO 8601。 */
    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

}
