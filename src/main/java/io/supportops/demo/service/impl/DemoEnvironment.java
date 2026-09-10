package io.supportops.demo.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.supportops.demo.service.DemoService;
import io.supportops.demo.vo.DemoAppInfo;
import io.supportops.demo.vo.DemoConfiguration;
import io.supportops.demo.vo.DemoHealth;
import io.supportops.demo.vo.DemoLogEntry;
import io.supportops.demo.vo.DemoLogs;
import io.supportops.demo.vo.DemoState;
import io.supportops.demo.vo.DownstreamResponse;
import io.supportops.demo.vo.SyncResult;

import jakarta.annotation.PreDestroy;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 产生真实本地 HTTP 交互的合成故障环境，不生成预设诊断结论。 */
@Component
public class DemoEnvironment implements DemoService {
    private final ObjectMapper json;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final List<DemoLogEntry> logs = new ArrayList<>();
    private Map<String, String> configuration;
    private String version = "2.0";
    private String scenario = "migration";
    private volatile boolean available = true;
    private boolean validCredential = true;
    private boolean logsAvailable = true;

    /** 启动独立回环下游并设置默认升级故障条件。 */
    public DemoEnvironment(ObjectMapper json) throws IOException {
        this.json = json;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/downstream", this::downstream);
        server.start();
        configure("migration");
    }

    /** 设置合成故障条件、清理内存日志并发起一次真实同步请求。 */
    public synchronized DemoState configure(String name) {
        if (!Set.of(
                        "healthy",
                        "unavailable",
                        "credentials",
                        "migration",
                        "stale-docs",
                        "insufficient",
                        "injection")
                .contains(name)) {
            throw new IllegalArgumentException("Unknown scenario");
        }
        scenario = name;
        version = "2.0";
        available = !name.equals("unavailable");
        validCredential = !name.equals("credentials");
        logsAvailable = !name.equals("insufficient");
        logs.clear();
        configuration =
                Set.of("migration", "stale-docs", "injection", "insufficient").contains(name)
                        ? Map.of("orders.path", "/v2/orders")
                        : Map.of("sync.targetPath", "/v2/orders");
        synchronizeOrder();
        return operatorView();
    }

    /** 应用新版路径键并验证同步，保留其他故障条件。 */
    public synchronized SyncResult repairConfiguration() {
        configuration = Map.of("sync.targetPath", "/v2/orders");
        return synchronizeOrder();
    }

    /** 使用实际生效配置请求本地下游，保存可审阅日志并返回业务结果。 */
    public synchronized SyncResult synchronizeOrder() {
        String target = configuration.getOrDefault("sync.targetPath", "/v1/orders");
        String requestId = UUID.randomUUID().toString();
        try {
            HttpRequest request =
                    HttpRequest.newBuilder(URI.create(baseUrl() + "/downstream" + target))
                            .timeout(Duration.ofSeconds(3))
                            .header(
                                    "Authorization",
                                    validCredential ? "Bearer demo-valid" : "Bearer demo-invalid")
                            .header("Content-Type", "application/json")
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            "{\"orderId\":\"demo-order\"}"))
                            .build();
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString());
            DemoLogEntry entry =
                    new DemoLogEntry(
                            requestId,
                            Instant.now().toString(),
                            "synchronize_order",
                            target,
                            response.statusCode(),
                            json.readValue(response.body(), DownstreamResponse.class),
                            null);
            logs.add(entry);
            if (logs.size() > 30) {
                logs.removeFirst();
            }
            return new SyncResult(
                    requestId, response.statusCode(), response.statusCode() == 200, null);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            logs.add(
                    new DemoLogEntry(
                            requestId,
                            Instant.now().toString(),
                            "synchronize_order",
                            null,
                            null,
                            null,
                            "TRANSPORT_FAILURE"));
            return new SyncResult(requestId, null, false, "TRANSPORT_FAILURE");
        }
    }

    // The handler must remain lock-free: synchronizeOrder holds this object's lock during HTTP.
    /** 根据实际路径、可用性与凭据返回下游状态；不获取同步请求持有的对象锁。 */
    private void downstream(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/downstream/health")) {
            send(exchange, available ? 200 : 503, Map.of("status", available ? "UP" : "DOWN"));
            return;
        }
        if (!available) {
            send(exchange, 503, Map.of("code", "TEMPORARILY_UNAVAILABLE"));
            return;
        }
        if (!"Bearer demo-valid".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            send(exchange, 401, Map.of("code", "INVALID_CREDENTIAL"));
            return;
        }
        if (!path.equals("/downstream/v2/orders")) {
            send(exchange, 410, Map.of("code", "ROUTE_RETIRED", "path", path));
            return;
        }
        send(exchange, 200, Map.of("accepted", true));
    }

    /** 将受控下游响应写为 JSON 并关闭输出流。 */
    private void send(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] bytes = json.writeValueAsBytes(value);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** 返回当前示例应用的版本、环境与事实采集时间。 */
    public synchronized DemoAppInfo appInfo() {
        return new DemoAppInfo("OrderBridge", version, "local-demo", Instant.now().toString());
    }

    /** 分别返回用户提供配置与实际生效配置，不暴露业务凭据。 */
    public synchronized DemoConfiguration config() {
        return new DemoConfiguration(
                configuration,
                Map.of(
                        "sync.targetPath",
                        configuration.getOrDefault("sync.targetPath", "/v1/orders")),
                true,
                Instant.now().toString());
    }

    /** 读取请求日志快照；证据源不可用时明确报告。 */
    public synchronized DemoLogs logs() {
        if (!logsAvailable) {
            throw new IllegalStateException("LOG_SOURCE_UNAVAILABLE");
        }
        return new DemoLogs(List.copyOf(logs), Instant.now().toString());
    }

    /** 通过真实 HTTP 请求获取下游健康状态，传输失败时不伪造正常结果。 */
    public DemoHealth health() {
        try {
            HttpResponse<String> response =
                    http.send(
                            HttpRequest.newBuilder(URI.create(baseUrl() + "/downstream/health"))
                                    .timeout(Duration.ofSeconds(3))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            return new DemoHealth(
                    response.statusCode(),
                    json.readTree(response.body()),
                    Instant.now().toString());
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("HEALTH_SOURCE_UNAVAILABLE");
        }
    }

    /** 组合人员操作所需的环境快照，场景标记不进入 MCP 事实接口。 */
    public synchronized DemoState operatorView() {
        return new DemoState(
                scenario,
                appInfo(),
                config(),
                logs.isEmpty()
                        ? new DemoLogEntry(null, null, null, null, null, null, null)
                        : logs.getLast(),
                true);
    }

    /** 返回本进程启动的随机回环端口地址。 */
    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** 停止合成下游服务并等待请求执行资源退出。 */
    @PreDestroy
    void close() {
        server.stop(0);
        executor.close();
    }
}
