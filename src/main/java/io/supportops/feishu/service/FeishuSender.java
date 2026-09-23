package io.supportops.feishu.service;

/** 飞书出站协议边界；接收者、正文和去重编号由持久化投递记录固定。 */
public interface FeishuSender {
    /** 发送纯文本私聊，成功返回平台消息编号；失败抛出不含凭据或响应正文的异常。 */
    String send(String openId, String text, String deliveryId) throws Exception;
}
