package io.supportops.feishu.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 飞书后台公开视图，不包含应用密钥、绑定码摘要或诊断正文。 */
public final class FeishuViews {
    /** 视图容器不允许实例化。 */
    private FeishuViews() {}

    /** 本地配置与连接状态；配置完整不代表已经鉴权成功。 */
    @Schema(name = "FeishuStatus", description = "飞书渠道状态，仅管理员可读")
    public record Status(
            @Schema(description = "是否启用长连接和回复发送") boolean enabled,
            @Schema(description = "应用编号、企业编号和密钥是否已填写；不表示鉴权成功") boolean configured,
            @Schema(description = "飞书应用编号，未配置时为空") String appId,
            @Schema(description = "允许接入的企业编号，未配置时为空") String tenantKey,
            @Schema(description = "连接状态：DISABLED 停用、CONNECTING 连接中、CONNECTED 已连接、RECONNECTING 重连中、ERROR 异常、STOPPED 已停止") String connection) {}

    /** 一次性绑定码仅在签发响应出现，十分钟过期。 */
    @Schema(name = "FeishuBindingCode", description = "一次性绑定码；仅此次响应返回明文")
    public record BindingCode(
            @Schema(description = "绑定记录 UUID") String id,
            @Schema(description = "工作台用户 UUID") String userId,
            @Schema(description = "在机器人私聊中发送此命令，禁止发送到群聊") String command,
            @Schema(description = "到期时间，Unix 毫秒") long expiresAt) {
        /** 避免调试输出泄漏一次性命令。 */
        @Override
        public String toString() {
            return "BindingCode[id=" + id + "]";
        }
    }

    /** 绑定列表只显示状态与已确认的平台身份。 */
    @Schema(name = "FeishuBinding", description = "身份绑定公开资料")
    public record Binding(
            @Schema(description = "绑定记录 UUID") String id,
            @Schema(description = "工作台用户 UUID") String userId,
            @Schema(description = "飞书应用内用户编号；尚未绑定或已撤销时为空", nullable = true) String openId,
            @Schema(description = "是否已撤销") boolean revoked,
            @Schema(description = "绑定码到期时间，Unix 毫秒；已消费时为空", nullable = true) Long expiresAt,
            @Schema(description = "创建时间，UTC ISO 8601") String createdAt) {}

    /** 投递状态用于区分诊断失败和消息未送达。 */
    @Schema(name = "FeishuDelivery", description = "一条冻结回复的投递结果")
    public record Delivery(
            @Schema(description = "回复 UUID，也是稳定发送去重标识") String id,
            @Schema(description = "NOTICE 绑定提示、ACCEPTED 受理提示、RESULT 结果") String kind,
            @Schema(description = "PENDING 待发、SENT 已发、FAILED 重试耗尽、UNKNOWN 超出安全重试窗口、BLOCKED 绑定失效") String state,
            @Schema(description = "发送尝试次数") int attempts,
            @Schema(description = "稳定脱敏错误码；正常为空") String errorCode) {}

    /** 最近收件记录不会返回问题、回答或一次性绑定命令。 */
    @Schema(name = "FeishuMessage", description = "最近一百条飞书收件与投递状态")
    public record Message(
            @Schema(description = "请求编号；诊断被受理后也是任务 UUID") String id,
            @Schema(description = "飞书原始消息编号") String messageId,
            @Schema(description = "所属工作台用户；未绑定通知为空", nullable = true) String userId,
            @Schema(description = "RECEIVED 待受理、WAITING 等待结果、FINISHED 已生成回复、BLOCKED 绑定失效") String state,
            @Schema(description = "脱敏受理错误码；正常为空") String errorCode,
            @Schema(description = "收件时间，UTC ISO 8601") String createdAt,
            @Schema(description = "该消息产生的回复及投递状态") List<Delivery> deliveries) {}
}
