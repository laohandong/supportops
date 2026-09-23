package io.supportops.feishu.transport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.service.im.v1.model.EventMessage;
import com.lark.oapi.service.im.v1.model.EventSender;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import io.supportops.feishu.dto.FeishuIncomingMessage;
import io.supportops.feishu.service.FeishuService;
import org.springframework.stereotype.Component;

/** 将 SDK 消息映射为明确业务输入，不暴露 HTTP 回调或把原始事件交给模型。 */
@Component
public class FeishuEventAdapter {
    private final FeishuService service;
    private final ObjectMapper json;

    /** 注入持久化收件服务和 JSON 解析器。 */
    public FeishuEventAdapter(FeishuService service, ObjectMapper json) {
        this.service = service;
        this.json = json;
    }

    /** 正文解析失败只忽略无效事件；持久化失败则向 SDK 抛错以触发平台重投。 */
    public void handle(P2MessageReceiveV1 event) {
        if (event == null || event.getHeader() == null || event.getEvent() == null) {
            return;
        }
        EventMessage message = event.getEvent().getMessage();
        EventSender sender = event.getEvent().getSender();
        if (message == null || sender == null || sender.getSenderId() == null
                || message.getContent() == null || message.getContent().length() > 100_000) {
            return;
        }
        FeishuIncomingMessage incoming;
        try {
            JsonNode body = json.readTree(message.getContent());
            if (body == null || !body.path("text").isTextual()) {
                return;
            }
            incoming = new FeishuIncomingMessage(event.getHeader().getAppId(), sender.getTenantKey(),
                    message.getMessageId(), sender.getSenderId().getOpenId(), sender.getSenderType(),
                    message.getChatType(), message.getMessageType(), body.path("text").asText(),
                    Long.parseLong(message.getCreateTime()));
        } catch (JsonProcessingException | NumberFormatException exception) {
            return;
        }
        service.receive(incoming);
    }
}
