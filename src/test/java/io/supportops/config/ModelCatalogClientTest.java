package io.supportops.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/** 本地 HTTP 夹具验证目录协议、鉴权和失败；不表示真实模型效果验证。 */
class ModelCatalogClientTest {
    /** 验证准确 ID、去重排序、空目录、拒绝重定向和错误正文隔离。 */
    @Test
    void readsCatalogAndReportsSafeFailures() throws Exception {
        AtomicReference<String> body =
                new AtomicReference<>(
                        "{\"data\":[{\"id\":\"z-model\"},{\"id\":\"a-model\"},{\"id\":\"z-model\"}]}");
        AtomicReference<Integer> status = new AtomicReference<>(200);
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/models",
                exchange -> {
                    authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    exchange.getResponseHeaders().set("Location", "/unexpected");
                    byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(status.get(), bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
        try {
            MockEnvironment env =
                    new MockEnvironment()
                            .withProperty("supportops.model.provider", "fixture")
                            .withProperty(
                                    "supportops.providers.fixture.base-url",
                                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                            .withProperty(
                                    "supportops.providers.fixture.chat-model", "fixture-model")
                            .withProperty(
                                    "supportops.providers.fixture.api-key-env", "FIXTURE_API_KEY")
                            .withProperty("FIXTURE_API_KEY", "fixture-secret");
            ProviderRegistry.Endpoint endpoint = new ProviderRegistry(env).dialogue();
            ModelCatalogClient client = new ModelCatalogClient(new ObjectMapper());
            assertThat(client.fetch(endpoint).models()).containsExactly("a-model", "z-model");
            assertThat(authorization.get()).isEqualTo("Bearer fixture-secret");
            body.set("{\"data\":[]}");
            assertThat(client.fetch(endpoint).models()).isEmpty();
            body.set("{\"error\":\"fixture-secret\"}");
            assertThatThrownBy(() -> client.fetch(endpoint))
                    .hasMessageContaining("MODEL_CATALOG_INVALID_RESPONSE")
                    .hasMessageNotContaining("fixture-secret");
            status.set(401);
            assertThatThrownBy(() -> client.fetch(endpoint))
                    .hasMessageContaining("MODEL_CATALOG_UNAUTHORIZED")
                    .hasMessageNotContaining("fixture-secret");
            status.set(302);
            assertThatThrownBy(() -> client.fetch(endpoint))
                    .hasMessageContaining("MODEL_CATALOG_FAILED");
            status.set(404);
            assertThatThrownBy(() -> client.fetch(endpoint))
                    .hasMessageContaining("MODEL_CATALOG_UNSUPPORTED");
        } finally {
            server.stop(0);
        }
    }
}
