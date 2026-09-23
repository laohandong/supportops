package io.supportops.feishu.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 持久化回复正文及固定发送标识；发送失败不能重新运行诊断。 */
@TableName("feishu_outbox")
public class FeishuOutboxEntity {
    /** 回复 UUID，也是飞书发送去重标识。 */
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    /** 关联收件记录 UUID。 */
    private String inboxId;

    /** NOTICE 绑定提示、ACCEPTED 受理提示、RESULT 诊断结果。 */
    private String kind;

    /** 冻结的纯文本回复，重试不得重新生成。 */
    private String content;

    /** PENDING 待发送、SENT 成功、FAILED 重试耗尽、UNKNOWN 超出去重窗口、BLOCKED 绑定失效。 */
    private String state;

    /** 已开始的发送次数；发送前落库。 */
    private Integer attempts;

    /** 首次发送时间，Unix 毫秒；未发送为空。 */
    private Long firstAttemptAt;

    /** 允许下次发送的时间，Unix 毫秒。 */
    private Long nextAttemptAt;

    /** 飞书返回的消息编号；尚未确认成功时为空。 */
    private String platformMessageId;

    /** 脱敏发送错误码，正常为空字符串。 */
    private String errorCode;

    /** 创建时间，UTC ISO 8601。 */
    private String createdAt;

    /** 返回回复 UUID，也是飞书发送去重标识。 */
    public String getId() {
        return id;
    }

    /** 设置回复 UUID，也是飞书发送去重标识。 */
    public void setId(String id) {
        this.id = id;
    }

    /** 返回关联收件记录 UUID。 */
    public String getInboxId() {
        return inboxId;
    }

    /** 设置关联收件记录 UUID。 */
    public void setInboxId(String inboxId) {
        this.inboxId = inboxId;
    }

    /** 返回NOTICE 绑定提示、ACCEPTED 受理提示、RESULT 诊断结果。 */
    public String getKind() {
        return kind;
    }

    /** 设置NOTICE 绑定提示、ACCEPTED 受理提示、RESULT 诊断结果。 */
    public void setKind(String kind) {
        this.kind = kind;
    }

    /** 返回冻结的纯文本回复，重试不得重新生成。 */
    public String getContent() {
        return content;
    }

    /** 设置冻结的纯文本回复，重试不得重新生成。 */
    public void setContent(String content) {
        this.content = content;
    }

    /** 返回PENDING 待发送、SENT 成功、FAILED 重试耗尽、UNKNOWN 超出去重窗口、BLOCKED 绑定失效。 */
    public String getState() {
        return state;
    }

    /** 设置PENDING 待发送、SENT 成功、FAILED 重试耗尽、UNKNOWN 超出去重窗口、BLOCKED 绑定失效。 */
    public void setState(String state) {
        this.state = state;
    }

    /** 返回已开始的发送次数；发送前落库。 */
    public Integer getAttempts() {
        return attempts;
    }

    /** 设置已开始的发送次数；发送前落库。 */
    public void setAttempts(Integer attempts) {
        this.attempts = attempts;
    }

    /** 返回首次发送时间，Unix 毫秒；未发送为空。 */
    public Long getFirstAttemptAt() {
        return firstAttemptAt;
    }

    /** 设置首次发送时间，Unix 毫秒；未发送为空。 */
    public void setFirstAttemptAt(Long firstAttemptAt) {
        this.firstAttemptAt = firstAttemptAt;
    }

    /** 返回允许下次发送的时间，Unix 毫秒。 */
    public Long getNextAttemptAt() {
        return nextAttemptAt;
    }

    /** 设置允许下次发送的时间，Unix 毫秒。 */
    public void setNextAttemptAt(Long nextAttemptAt) {
        this.nextAttemptAt = nextAttemptAt;
    }

    /** 返回飞书返回的消息编号；尚未确认成功时为空。 */
    public String getPlatformMessageId() {
        return platformMessageId;
    }

    /** 设置飞书返回的消息编号；尚未确认成功时为空。 */
    public void setPlatformMessageId(String platformMessageId) {
        this.platformMessageId = platformMessageId;
    }

    /** 返回脱敏发送错误码，正常为空字符串。 */
    public String getErrorCode() {
        return errorCode;
    }

    /** 设置脱敏发送错误码，正常为空字符串。 */
    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
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
