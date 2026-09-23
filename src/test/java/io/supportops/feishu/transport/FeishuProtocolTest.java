package io.supportops.feishu.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.any;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lark.oapi.Client;
import com.lark.oapi.core.utils.Jsons;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.supportops.feishu.dto.FeishuIncomingMessage;
import io.supportops.feishu.service.FeishuService;
import java.net.InetSocketAddress;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** 官方 SDK 配合本地 HTTP 夹具验证实际鉴权及发送格式，不声称已连接真实飞书。 */
class FeishuProtocolTest {
    private final ObjectMapper json = new ObjectMapper();

    /** 同一冻结请求重复发送时保持收件人、正文及 uuid，应用密钥仅用于令牌请求。 */
    @Test
    void officialSdkSendsPrivateTextWithStableUuidAndRedactedFailures() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<JsonNode> bodies = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        List<String> auth = new ArrayList<>();
        AtomicBoolean fail = new AtomicBoolean();
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().toString());
            JsonNode body = json.readTree(exchange.getRequestBody());
            bodies.add(body);
            auth.add(exchange.getRequestHeaders().getFirst("Authorization"));
            if (exchange.getRequestURI().getPath().endsWith("/tenant_access_token/internal")) {
                reply(exchange, "{\"code\":0,\"tenant_access_token\":\"synthetic-feishu-token\",\"expire\":7200}");
            } else {
                reply(exchange, fail.get() ? "{\"code\":9999,\"msg\":\"synthetic-private-platform-error\"}"
                        : "{\"code\":0,\"data\":{\"message_id\":\"om_fixture_result\"}}");
            }
        });
        server.start();
        try {
            Client client = Client.newBuilder("cli_test_" + UUID.randomUUID(), "synthetic-feishu-secret")
                    .openBaseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                    .requestTimeout(3, TimeUnit.SECONDS).logReqAtDebug(false).build();
            SdkFeishuSender sender = new SdkFeishuSender(client, json);
            String text = "合成诊断\n引用：doc_123；包含引号\"与反斜线\\";
            String id = UUID.randomUUID().toString();
            assertThat(sender.send("ou_fixture", text, id)).isEqualTo("om_fixture_result");
            assertThat(sender.send("ou_fixture", text, id)).isEqualTo("om_fixture_result");
            assertThat(paths).hasSize(3);
            assertThat(paths.getFirst()).endsWith("/open-apis/auth/v3/tenant_access_token/internal");
            assertThat(bodies.getFirst().path("app_secret").asText()).isEqualTo("synthetic-feishu-secret");
            assertThat(paths.get(1)).isEqualTo("/open-apis/im/v1/messages?receive_id_type=open_id");
            assertThat(auth.get(1)).isEqualTo("Bearer synthetic-feishu-token");
            assertThat(bodies.get(1)).isEqualTo(bodies.get(2));
            assertThat(bodies.get(1).path("uuid").asText()).isEqualTo(id);
            assertThat(bodies.get(1).path("receive_id").asText()).isEqualTo("ou_fixture");
            assertThat(bodies.get(1).path("msg_type").asText()).isEqualTo("text");
            assertThat(json.readTree(bodies.get(1).path("content").asText()).path("text").asText()).isEqualTo(text);
            assertThat(bodies.get(1).toString()).doesNotContain("synthetic-feishu-secret");
            fail.set(true);
            assertThatThrownBy(() -> sender.send("ou_fixture", text, id))
                    .isInstanceOf(IllegalStateException.class).hasMessage("FEISHU_SEND_FAILED").hasNoCause();
        } finally {
            server.stop(0);
        }
    }

    /** SDK 的真实消息字段映射到明确业务类型，不使用显示名或请求中的本地用户编号。 */
    @Test
    void sdkReceiveEventPreservesIdentityScopeAndMessageId() {
        FeishuService service = mock(FeishuService.class);
        FeishuEventAdapter adapter = new FeishuEventAdapter(service, json);
        P2MessageReceiveV1 event = event("{\"text\":\"合成问题\"}", "1789992000000");
        adapter.handle(event);
        verify(service).receive(new FeishuIncomingMessage("cli_fixture", "tenant_fixture", "om_fixture",
                "ou_fixture", "user", "p2p", "text", "合成问题", 1789992000000L));
    }

    /** 畸形载荷不触发业务；持久化异常不被吞掉以免平台误以为已受理。 */
    @Test
    void malformedEventIsIgnoredButDatabaseFailureIsNotAcknowledged() {
        FeishuService service = mock(FeishuService.class);
        FeishuEventAdapter adapter = new FeishuEventAdapter(service, json);
        adapter.handle(event("not-json", "1789992000000"));
        adapter.handle(event("{\"text\":{}}", "1789992000000"));
        adapter.handle(event("{\"text\":\"问题\"}", "invalid"));
        adapter.handle(null);
        verifyNoInteractions(service);
        doThrow(new IllegalStateException("SYNTHETIC_DB_FAILURE")).when(service).receive(any());
        assertThatThrownBy(() -> adapter.handle(event("{\"text\":\"问题\"}", "1789992000000")))
                .hasMessage("SYNTHETIC_DB_FAILURE");
    }

    /** 按官方 SDK 的序列化定义创建接收事件夹具。 */
    private P2MessageReceiveV1 event(String content, String time) {
        ObjectNode payload = json.createObjectNode().put("schema", "2.0");
        payload.putObject("header").put("app_id", "cli_fixture");
        ObjectNode data = payload.putObject("event");
        ObjectNode sender = data.putObject("sender");
        sender.put("sender_type", "user").put("tenant_key", "tenant_fixture");
        sender.putObject("sender_id").put("open_id", "ou_fixture");
        data.putObject("message").put("message_id", "om_fixture").put("chat_type", "p2p")
                .put("message_type", "text").put("content", content).put("create_time", time);
        return Jsons.DEFAULT.fromJson(payload.toString(), P2MessageReceiveV1.class);
    }

    /** 返回 UTF-8 JSON 协议响应，测试不使用外部凭据。 */
    private void reply(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
