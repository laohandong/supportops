package io.supportops.feishu.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.Client;
import com.lark.oapi.service.im.v1.model.CreateMessageReq;
import com.lark.oapi.service.im.v1.model.CreateMessageReqBody;
import com.lark.oapi.service.im.v1.model.CreateMessageResp;
import io.supportops.feishu.FeishuProperties;
import io.supportops.feishu.service.FeishuSender;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 使用官方 SDK 向固定飞书端点发送纯文本私聊；不允许配置任意收件接口地址。 */
@Component
public class SdkFeishuSender implements FeishuSender {
    private final Client client;
    private final ObjectMapper json;

    /** 禁用或缺少配置时不构造外部客户端，不触发任何网络请求。 */
    @Autowired
    public SdkFeishuSender(FeishuProperties properties, ObjectMapper json) {
        this(properties.enabled() && properties.configured() ? Client.newBuilder(properties.appId(), properties.appSecret())
                .requestTimeout(10, TimeUnit.SECONDS).logReqAtDebug(false).build() : null, json);
    }

    /** 注入 SDK 客户端便于用本地 HTTP 夹具验证协议；生产入口固定使用官方端点。 */
    SdkFeishuSender(Client client, ObjectMapper json) {
        this.client = client;
        this.json = json;
    }

    /** 固定 open_id 收件类型和 text 内容，所有重试复用持久化 uuid。 */
    @Override
    public String send(String openId, String text, String deliveryId) throws Exception {
        if (client == null) {
            throw new IllegalStateException("FEISHU_NOT_CONFIGURED");
        }
        String content = json.createObjectNode().put("text", text).toString();
        CreateMessageReq request = CreateMessageReq.newBuilder().receiveIdType("open_id")
                .createMessageReqBody(CreateMessageReqBody.newBuilder().receiveId(openId)
                        .msgType("text").content(content).uuid(deliveryId).build()).build();
        try {
            CreateMessageResp response = client.im().v1().message().create(request);
            if (!response.success() || response.getData() == null || response.getData().getMessageId() == null) {
                throw new IllegalStateException("FEISHU_SEND_REJECTED");
            }
            return response.getData().getMessageId();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exception;
        } catch (Exception exception) {
            // SDK 异常可能携带 HTTP 内容，不能向上层转交原始异常或 cause。
            throw new IllegalStateException("FEISHU_SEND_FAILED");
        }
    }
}
