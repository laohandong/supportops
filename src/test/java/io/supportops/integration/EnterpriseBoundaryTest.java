package io.supportops.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.supportops.integration.dto.ConnectorConfig;
import io.supportops.integration.service.support.EnvironmentAdmission;
import io.supportops.integration.service.support.PinnedHttpSource;
import io.supportops.integration.service.support.SourceProtocol;
import io.supportops.project.vo.TargetView;
import io.supportops.knowledge.service.ElasticKnowledgeIndex;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.ConnectException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/** 用真实回环HTTP核查协议拒绝及有限资源边界，不代表现场或模型效果。 */
class EnterpriseBoundaryTest {
    private final ObjectMapper json = new ObjectMapper();
    /** 企业内部ES服务名仅在企业模式固定端口放行，外部HTTP和本地默认模式继续拒绝。 */
    @Test void internalElasticsearchRequiresEnterpriseBoundary() {
        new ElasticKnowledgeIndex(json, "http://elasticsearch:9200", "", "", "supportops", true);
        assertThatThrownBy(() -> new ElasticKnowledgeIndex(json, "http://elasticsearch:9200", "", "", "supportops", false))
                .hasMessage("INVALID_ES_CONFIGURATION");
        assertThatThrownBy(() -> new ElasticKnowledgeIndex(json, "http://external.invalid:9200", "", "", "supportops", true))
                .hasMessage("INVALID_ES_CONFIGURATION");
        assertThatThrownBy(() -> new ElasticKnowledgeIndex(json, "http://elasticsearch:9201", "", "", "supportops", true))
                .hasMessage("INVALID_ES_CONFIGURATION");
    }
    private final String request = UUID.randomUUID().toString();
    private final TargetView target = new TargetView(UUID.randomUUID().toString(), "项目", UUID.randomUUID().toString(),
            "erp", "系统", UUID.randomUUID().toString(), "test", "测试", "erp-product", "2.0", UUID.randomUUID().toString());
    /** 精确外壳验证拒绝身份漂移、重复字段、空类型和秘密配置。 */
    @Test void protocolRejectsUntrustedStructure() throws Exception {
        SourceProtocol protocol = new SourceProtocol(json);
        ObjectNode envelope = envelope();
        assertThat(protocol.validate(bytes(envelope), request, target, "APP_INFO", 5).target().systemCode()).isEqualTo("erp");
        envelope.withObject("target").put("systemCode", "other");
        rejected(() -> protocol.validate(bytes(envelope), request, target, "APP_INFO", 5), "TARGET_IDENTITY_MISMATCH");
        ObjectNode stale = envelope(); stale.put("observedAt", Instant.now().minusSeconds(301).toString());
        rejected(() -> protocol.validate(bytes(stale), request, target, "APP_INFO", 5), "TARGET_OBSERVATION_STALE");
        ObjectNode extra = envelope(); extra.put("instructions", "扩大工具权限");
        rejected(() -> protocol.validate(bytes(extra), request, target, "APP_INFO", 5), "TARGET_SCHEMA_INVALID");
        String duplicate = json.writeValueAsString(envelope()).replace("\"protocolVersion\":\"1.0\"", "\"protocolVersion\":\"1.0\",\"protocolVersion\":\"1.0\"");
        rejected(() -> protocol.validate(duplicate.getBytes(StandardCharsets.UTF_8), request, target, "APP_INFO", 5), "TARGET_SCHEMA_INVALID");
        ObjectNode secret = envelope(); secret.set("data", json.readTree("{\"items\":[{\"key\":\"api.token\",\"value\":\"synthetic-private\",\"redacted\":false,\"valueSource\":\"source\"}]}"));
        rejected(() -> protocol.validate(bytes(secret), request, target, "EFFECTIVE_CONFIG", 5), "TARGET_SCHEMA_INVALID");
    }
    /** 两次DNS解析变化后拒绝新地址，实际源只收到已批准的首次连接。 */
    @Test void dnsChangeAndPathEscapeNeverReachAnotherAddress() throws Exception {
        AtomicInteger calls = new AtomicInteger(), lookups = new AtomicInteger();
        HttpServer source = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        source.createContext("/v1/app-info", exchange -> {
            calls.incrementAndGet();exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200,2);exchange.getResponseBody().write(new byte[]{123,125});exchange.close();
        });source.start();
        try {
            PinnedHttpSource client = new PinnedHttpSource(host -> new InetAddress[]{InetAddress.getByName(lookups.incrementAndGet()==1 ? "127.0.0.1" : "127.0.0.2")});
            ConnectorConfig config = new ConnectorConfig("http://fixed-source.internal:" + source.getAddress().getPort(),
                    Set.of("127.0.0.1"),Set.of("APP_INFO"),Set.of("APP_INFO"),true);
            assertThat(client.get(config,"APP_INFO","synthetic-private",request,null,"")).containsExactly((byte)123,(byte)125);
            assertThatThrownBy(() -> client.get(config,"APP_INFO","synthetic-private",request,null,""))
                    .isInstanceOf(ResponseStatusException.class);
            assertThat(calls.get()).isEqualTo(1);
            ConnectorConfig escaped = new ConnectorConfig("http://127.0.0.1:" + source.getAddress().getPort() + "/safe/../secret",
                    Set.of("127.0.0.1"),Set.of("APP_INFO"),Set.of("APP_INFO"),true);
            rejected(() -> client.validate(escaped),"INVALID_CONNECTOR_NETWORK_POLICY");
            assertThat(calls.get()).isEqualTo(1);
        } finally { source.stop(0); }
    }
    /** IPv6精确地址可实际连接，链路本地、组播及未指定地址均在连接前拒绝。 */
    @Test void ipv6LiteralUsesApprovedActualAddress() throws Exception {
        HttpServer source = HttpServer.create(new InetSocketAddress(InetAddress.getByName("::1"), 0), 0);
        source.createContext("/v1/app-info", exchange -> {
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,2);
            exchange.getResponseBody().write(new byte[]{123,125});exchange.close();
        });source.start();
        try {
            PinnedHttpSource client = new PinnedHttpSource();
            ConnectorConfig config = new ConnectorConfig("http://[::1]:" + source.getAddress().getPort(),Set.of("::1"),Set.of("APP_INFO"),Set.of("APP_INFO"),true);
            assertThat(client.get(config,"APP_INFO","synthetic-private",request,null,"")).hasSize(2);
            for(String forbidden : Set.of("fe80::1","ff02::1","::")) {
                rejected(() -> client.validate(new ConnectorConfig("https://fixed-source.internal",Set.of(forbidden),Set.of("APP_INFO"),Set.of("APP_INFO"),false)),"INVALID_CONNECTOR_NETWORK_POLICY");
            }
        } finally { source.stop(0); }
    }
    /** 真实正文停滞由空闲预算终止，不依赖服务器主动关闭且不回显秘密。 */
    @Test void stalledBodyExitsWithinReadBudget() throws Exception {
        HttpServer source = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        source.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        source.createContext("/stall/v1/app-info", exchange -> {
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,20);
            exchange.getResponseBody().write(123);exchange.getResponseBody().flush();
            try { Thread.sleep(12000); } catch(InterruptedException exception) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });source.start();
        try {
            long started = System.nanoTime();
            assertThatThrownBy(() -> new PinnedHttpSource().get(config(source,"stall"),"APP_INFO","synthetic-private",request,null,""))
                    .isInstanceOf(ResponseStatusException.class).hasMessageNotContaining("synthetic-private");
            assertThat(Duration.ofNanos(System.nanoTime()-started)).isLessThan(Duration.ofSeconds(8));
        } finally { source.stop(0); }
    }
    /** 实际连接遇到重定向、压缩及超长正文不会读取其他目标。 */
    @Test void realHttpRejectsRedirectCompressionAndOversize() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer source = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        source.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        source.createContext("/redirect/v1/app-info", exchange -> {
            calls.incrementAndGet(); exchange.getResponseHeaders().set("Location", "/other/v1/app-info");
            exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        source.createContext("/other/v1/app-info", exchange -> { calls.addAndGet(100); exchange.close(); });
        source.createContext("/compressed/v1/app-info", exchange -> {
            calls.incrementAndGet(); exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Content-Encoding", "gzip"); exchange.sendResponseHeaders(200, 2);
            exchange.getResponseBody().write(new byte[] {123, 125}); exchange.close();
        });
        source.createContext("/large/v1/app-info", exchange -> {
            calls.incrementAndGet(); exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 1048577); exchange.close();
        });
        source.start();
        try {
            PinnedHttpSource client = new PinnedHttpSource();
            for (String path : new String[] {"redirect", "compressed", "large"}) {
                assertThatThrownBy(() -> client.get(config(source, path), "APP_INFO", "synthetic-private", request, null, ""))
                        .isInstanceOf(ResponseStatusException.class).hasMessageNotContaining("synthetic-private");
            }
            assertThat(calls.get()).isEqualTo(3);
            ConnectorConfig forbidden = new ConnectorConfig("http://127.0.0.1:" + source.getAddress().getPort(),
                    Set.of("169.254.169.254"), Set.of("APP_INFO"), Set.of("APP_INFO"), true);
            assertThatThrownBy(() -> client.get(forbidden, "APP_INFO", "synthetic-private", request, null, ""))
                    .isInstanceOf(ResponseStatusException.class);
            assertThat(calls.get()).isEqualTo(3);
        } finally { source.stop(0); }
    }
    /** DNS停滞在两秒解析预算内退出，取消解析后源端零请求且错误不泄漏凭据。 */
    @Test void stalledDnsExitsWithoutOpeningConnection() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicBoolean interrupted = new AtomicBoolean();
        HttpServer source = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        source.createContext("/v1/app-info", exchange -> {
            calls.incrementAndGet();
            exchange.close();
        });
        source.start();
        try {
            PinnedHttpSource client = new PinnedHttpSource(host -> {
                try {
                    Thread.sleep(6000);
                }
                catch (InterruptedException exception) {
                    interrupted.set(true);
                    throw exception;
                }
                return new InetAddress[] {InetAddress.getByName("127.0.0.1")};
            });
            long started = System.nanoTime();
            rejected(() -> client.get(config(source, ""), "APP_INFO", "synthetic-private", request, null, ""), "TARGET_DNS_FAILED");
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isBetween(Duration.ofSeconds(1), Duration.ofSeconds(4));
            for (int attempt = 0; attempt < 100 && !interrupted.get(); attempt++) {
                Thread.sleep(10);
            }
            assertThat(interrupted).isTrue();
            assertThat(calls).hasValue(0);
        } finally {
            source.stop(0);
        }
    }
    /** 填满真实回环监听队列，核对连接预算；不使用企业地址或外部黑洞网络。 */
    @Test void fullLoopbackBacklogExitsWithinConnectBudget() throws Exception {
        List<Socket> occupied = new ArrayList<>();
        try (ServerSocket listener = new ServerSocket()) {
            listener.bind(new InetSocketAddress("127.0.0.1", 0), 1);
            boolean full = false;
            boolean immediateRefusal = false;
            for (int attempt = 0; attempt < 64; attempt++) {
                Socket candidate = new Socket();
                try {
                    candidate.connect(new InetSocketAddress("127.0.0.1", listener.getLocalPort()), 100);
                    occupied.add(candidate);
                } catch (SocketTimeoutException exception) {
                    candidate.close();
                    full = true;
                    break;
                }
                catch (ConnectException exception) {
                    candidate.close();
                    assertThat(System.getProperty("os.name")).startsWith("Windows");
                    immediateRefusal = true;
                    full = true;
                    break;
                }
                catch (Exception exception) {
                    candidate.close();
                    throw exception;
                }
            }
            assertThat(full).as("真实监听队列已饱和").isTrue();
            ConnectorConfig config = new ConnectorConfig("http://127.0.0.1:" + listener.getLocalPort(),
                    Set.of("127.0.0.1"), Set.of("APP_INFO"), Set.of("APP_INFO"), true);
            long started = System.nanoTime();
            rejected(() -> new PinnedHttpSource().get(config, "APP_INFO", "synthetic-private", request, null, ""), "TARGET_CONNECTION_FAILED");
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
            // Windows满队列立即发送RST；Linux真实连接超时仍核对三秒预算。
            if (immediateRefusal) {
                assertThat(elapsed).isLessThan(Duration.ofSeconds(1));
            } else {
                assertThat(elapsed).isBetween(Duration.ofSeconds(2), Duration.ofSeconds(5));
            }
        } finally {
            for (Socket socket : occupied) {
                socket.close();
            }
        }
    }
    /** 持续滴流不会触发空闲超时，但仍必须被十秒总预算关闭。 */
    @Test void continuouslyArrivingBodyCannotExtendTotalBudget() throws Exception {
        HttpServer source = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            source.setExecutor(workers);
            source.createContext("/drip/v1/app-info", exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, 64);
                try {
                    for (int index = 0; index < 64; index++) {
                        exchange.getResponseBody().write(32);
                        exchange.getResponseBody().flush();
                        Thread.sleep(250);
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                finally {
                    exchange.close();
                }
            });
            source.start();
            try {
                long started = System.nanoTime();
                rejected(() -> new PinnedHttpSource().get(config(source, "drip"), "APP_INFO", "synthetic-private", request, null, ""), "TARGET_CONNECTION_FAILED");
                assertThat(Duration.ofNanos(System.nanoTime() - started)).isBetween(Duration.ofSeconds(8), Duration.ofSeconds(13));
            } finally {
                source.stop(0);
            }
        }
    }
    /** HTTP错误保持精确安全原因码，缺可选能力在连接前拒绝，超深JSON不会进入来源证据。 */
    @Test void optionalCapabilitiesHttpFailuresAndDeepJsonAreRejected() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer source = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        for (int status : new int[] {401, 403, 503}) {
            source.createContext("/status" + status + "/v1/app-info", exchange -> {
                calls.incrementAndGet();
                exchange.sendResponseHeaders(status, 17);
                exchange.getResponseBody().write("synthetic-private".getBytes(StandardCharsets.UTF_8));
                exchange.close();
            });
        }
        source.start();
        try {
            PinnedHttpSource client = new PinnedHttpSource();
            rejected(() -> client.get(config(source, "status401"), "APP_INFO", "synthetic-private", request, null, ""), "TARGET_AUTHENTICATION_FAILED");
            rejected(() -> client.get(config(source, "status403"), "APP_INFO", "synthetic-private", request, null, ""), "TARGET_PERMISSION_DENIED");
            rejected(() -> client.get(config(source, "status503"), "APP_INFO", "synthetic-private", request, null, ""), "TARGET_HTTP_FAILED");
            for (String capability : Set.of("EFFECTIVE_CONFIG", "RECENT_LOGS", "DOWNSTREAM_HEALTH")) {
                rejected(() -> client.get(config(source, "status503"), capability, "synthetic-private", request, null, ""), "CAPABILITY_NOT_GRANTED");
            }
            assertThat(calls).hasValue(3);
            ObjectNode deep = envelope();
            deep.set("data", json.readTree("[".repeat(25) + "0" + "]".repeat(25)));
            rejected(() -> new SourceProtocol(json).validate(bytes(deep), request, target, "APP_INFO", 5), "TARGET_SCHEMA_INVALID");
        } finally {
            source.stop(0);
        }
    }
    /** 终态尚未归还实际资源时名额仍占用，环境写入和全局维护均排他。 */
    @Test void admissionRetainsActualResourceBoundary() {
        EnvironmentAdmission admission = new EnvironmentAdmission(true, 2);
        admission.acquire("first", false); admission.acquire("second", false);
        rejected(() -> admission.acquire("third", true), "CAPACITY_EXCEEDED");
        rejected(() -> admission.environment("first", () -> "write"), "DIAGNOSIS_IN_PROGRESS");
        rejected(() -> admission.globally(() -> "maintenance"), "DIAGNOSIS_IN_PROGRESS");
        admission.release("first"); admission.release("second");
        assertThat(admission.globally(() -> "maintenance")).isEqualTo("maintenance");
        assertThat(admission.environment("first", () -> "write")).isEqualTo("write");
    }
    /** 返回严格匹配目标的合成外壳。 */
    private ObjectNode envelope() throws Exception {
        ObjectNode value = (ObjectNode) json.readTree("{\"protocolVersion\":\"1.0\",\"target\":{\"systemCode\":\"erp\",\"environmentCode\":\"test\",\"productKey\":\"erp-product\",\"productVersion\":\"2.0\"},\"source\":{\"kind\":\"APPLICATION\",\"instanceId\":\"synthetic-instance\"},\"data\":{\"applicationName\":\"合成系统\",\"capabilities\":[\"APP_INFO\"]}}");
        value.put("requestId", request); value.put("observedAt", Instant.now().toString()); return value;
    }
    /** 将已知合成结构编码为协议字节。 */
    private byte[] bytes(ObjectNode value) { return value.toString().getBytes(StandardCharsets.UTF_8); }
    /** 本地HTTP明确标记合成，只登记真实监听地址。 */
    private ConnectorConfig config(HttpServer server, String path) {
        return new ConnectorConfig("http://127.0.0.1:" + server.getAddress().getPort() + "/" + path,
                Set.of("127.0.0.1"), Set.of("APP_INFO"), Set.of("APP_INFO"), true);
    }
    /** 安全错误只核对原因码，不能含现场秘密或正文。 */
    private void rejected(Runnable operation, String code) {
        assertThatThrownBy(operation::run).isInstanceOf(ResponseStatusException.class).hasMessageContaining(code);
    }
}
