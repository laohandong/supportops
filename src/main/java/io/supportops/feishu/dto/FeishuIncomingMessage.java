package io.supportops.feishu.dto;

/** 经官方 SDK 解析的消息；服务层仍校验应用、企业、发送者及私聊类型。 */
public record FeishuIncomingMessage(
        String appId, String tenantKey, String messageId, String openId,
        String senderType, String chatType, String messageType, String text, long createdAtMillis) {}
