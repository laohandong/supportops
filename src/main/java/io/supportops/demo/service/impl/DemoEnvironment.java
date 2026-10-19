package io.supportops.demo.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.supportops.demo.service.DemoService;
import io.supportops.demo.service.support.BoundedDemoHttp;
import io.supportops.remediation.constant.RemediationContract;
import io.supportops.remediation.service.OrderBridgeRemediationAdapter;
import io.supportops.remediation.service.support.RemediationJson;
import io.supportops.remediation.vo.RemediationViews.ApplicationReceipt;
import io.supportops.remediation.vo.RemediationViews.Snapshot;
import io.supportops.remediation.vo.RemediationViews.Receipt;
import io.supportops.remediation.vo.RemediationViews.Verification;
import io.supportops.user.CurrentUser;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.TreeMap;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 产生真实本地 HTTP 交互的合成故障环境，不生成预设诊断结论。 */
@Component
public class DemoEnvironment implements DemoService, OrderBridgeRemediationAdapter {
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
    private final String instanceId = UUID.randomUUID().toString();
    private long revision;
    private String changeSource = "STARTUP";
    private final Map<String, Operation> operations = new HashMap<>();
    private final Set<String> inFlightOperations = ConcurrentHashMap.newKeySet();

    /** 有界原操作回执；验证是否开始与结果分别保存，禁止响应丢失后重发。 */
    private static final class Operation {
        private final Snapshot before;
        private final String requestId;
        private ApplicationReceipt application;
        private Verification verification;
        private boolean verificationStarted;
        private final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(RemediationContract.EXECUTION_BUDGET_SECONDS);

        /** 预留时即记录身份，尚未应用只能由本实例回执确认。 */
        private Operation(Snapshot before, String operationId, String requestId) {
            this.before = before;
            this.requestId = requestId;
            this.application = new ApplicationReceipt(operationId, before.instanceId(), "NOT_STARTED",
                    before.revision(), before.revision(), before.provided(), before.provided(), null, "");
        }
    }

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
        revision++;
        changeSource = "SCENARIO:" + name + ":" + (CurrentUser.id() == null ? "INTERNAL" : CurrentUser.id());
        configuration =
                Set.of("migration", "stale-docs", "injection", "insufficient").contains(name)
                        ? Map.of("orders.path", "/v2/orders")
                        : Map.of("sync.targetPath", "/v2/orders");
        synchronizeOrder();
        return operatorView();
    }

    /** 只读取必要事实；指纹包括真实故障条件，不能依赖场景名称。 */
    @Override
    public String instanceId() {
        return instanceId;
    }

    /** 只读取必要事实；指纹包括真实故障条件，不能依赖场景名称。 */
    @Override
    public synchronized Snapshot snapshot() {
        Map<String, String> provided = Map.copyOf(new TreeMap<>(configuration));
        String effective = configuration.getOrDefault("sync.targetPath", "/v1/orders");
        RemediationJson canonical = new RemediationJson();
        String fingerprint = RemediationJson.hash(canonical.write(List.of(version, provided, effective,
                available, validCredential, logsAvailable)));
        boolean applicable = "2.0".equals(version) && available && validCredential && logsAvailable
                && "/v2/orders".equals(provided.get("orders.path"))
                && !provided.containsKey("sync.targetPath") && "/v1/orders".equals(effective);
        return new Snapshot(RemediationContract.TARGET, instanceId, revision, version, provided,
                effective, fingerprint, Instant.now().toString(), applicable, changeSource);
    }

    /** 固定变换保留无关配置；不接受客户端 after 值。 */
    @Override
    public Map<String, String> transformed(Snapshot before) {
        Map<String, String> after = new TreeMap<>(before.provided());
        after.remove("orders.path");
        after.put("sync.targetPath", RemediationContract.PATH);
        return Map.copyOf(after);
    }

    /** 受理之前预留回执，未决回执不能被驱逐。 */
    @Override
    public synchronized void reserve(String operationId, String requestId, Snapshot before) {
        Operation existing = operations.get(operationId);
        if (existing != null) {
            if (!existing.requestId.equals(requestId) || !existing.before.equals(before)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "REMEDIATION_OPERATION_CONFLICT");
            }
            return;
        }
        if (operations.size() >= RemediationContract.RECEIPT_CAPACITY) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "REMEDIATION_RECEIPT_CAPACITY");
        }
        operations.put(operationId, new Operation(before, operationId, requestId));
        inFlightOperations.add(operationId);
    }

    /** 原子核对实例、修订和指纹再修改；回执与配置处于同一临界区。 */
    @Override
    public synchronized void apply(String operationId) {
        Operation operation = operation(operationId);
        if (!"NOT_STARTED".equals(operation.application.status())) {
            return;
        }
        Snapshot actual = snapshot();
        Snapshot before = operation.before;
        boolean matches = before.instanceId().equals(actual.instanceId())
                && before.revision() == actual.revision()
                && before.fingerprint().equals(actual.fingerprint()) && actual.applicable();
        boolean budgetExpired = System.nanoTime() >= operation.deadline;
        if (!matches || budgetExpired) {
            operation.application = new ApplicationReceipt(operationId, instanceId, "NOT_APPLIED",
                    before.revision(), actual.revision(), before.provided(), actual.provided(), null,
                    budgetExpired ? "REMEDIATION_EXECUTION_BUDGET_EXCEEDED" : "REMEDIATION_ENVIRONMENT_CHANGED");
            return;
        }
        configuration = transformed(before);
        revision++;
        changeSource = "REMEDIATION:" + operationId;
        operation.application = new ApplicationReceipt(operationId, instanceId, "APPLIED",
                before.revision(), revision, before.provided(), Map.copyOf(configuration),
                Instant.now().toString(), "");
    }

    /** 新请求至多一次；HTTP 200 还须满足 accepted、回显身份、路径和配置读回。 */
    @Override
    public void verify(String operationId) {
        Operation operation;
        String path;
        boolean credential;
        synchronized (this) {
            operation = operation(operationId);
            if (operation.verificationStarted || !"APPLIED".equals(operation.application.status())) {
                return;
            }
            operation.verificationStarted = true;
            path = configuration.getOrDefault("sync.targetPath", "/v1/orders");
            credential = validCredential;
        }
        String startedAt = Instant.now().toString();
        Integer httpStatus = null;
        Boolean accepted = null;
        String echoedRequestId = null;
        String echoedPath = null;
        String status = "UNKNOWN";
        String error = "VERIFICATION_TRANSPORT_UNKNOWN";
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/downstream" + path))
                    .timeout(Duration.ofSeconds(3))
                    .header("Authorization", credential ? "Bearer demo-valid" : "Bearer demo-invalid")
                    .header("X-OrderBridge-Request-Id", operation.requestId)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"orderId\":\"demo-order\"}"))
                    .build();
            long remaining = Math.min(TimeUnit.SECONDS.toNanos(RemediationContract.HTTP_BUDGET_SECONDS),
                    operation.deadline - System.nanoTime());
            HttpResponse<String> response = BoundedDemoHttp.send(http, request, Duration.ofNanos(remaining),
                    RemediationContract.MAX_EVIDENCE_BYTES);
            httpStatus = response.statusCode();
            if (response.body().getBytes(StandardCharsets.UTF_8).length
                    > RemediationContract.MAX_EVIDENCE_BYTES) {
                error = "REMEDIATION_EVIDENCE_TOO_LARGE";
            } else {
                JsonNode body = json.readTree(response.body());
                accepted = body != null && body.path("accepted").isBoolean() ? body.path("accepted").booleanValue() : null;
                echoedRequestId = body == null ? null : body.path("requestId").asText(null);
                echoedPath = body == null ? null : body.path("path").asText(null);
                Snapshot readback = snapshot();
                boolean passed = httpStatus == 200 && Boolean.TRUE.equals(accepted)
                        && operation.requestId.equals(echoedRequestId) && path.equals(echoedPath)
                        && RemediationContract.PATH.equals(path)
                        && readback.provided().equals(operation.application.after())
                        && readback.revision() == operation.application.afterRevision()
                        && System.nanoTime() <= operation.deadline;
                status = passed ? "PASSED" : "FAILED";
                error = passed ? "" : "VERIFICATION_REJECTED";
            }
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (exception instanceof TimeoutException) {
                error = "VERIFICATION_BUDGET_EXCEEDED";
            } else if ("REMEDIATION_EVIDENCE_TOO_LARGE".equals(exception.getMessage())) {
                error = "REMEDIATION_EVIDENCE_TOO_LARGE";
            }
            // 已收到明确响应而结构损坏是验证失败；传输异常仍可能已有业务副作用。
            if (httpStatus != null) {
                status = "FAILED";
                error = "VERIFICATION_INVALID_RESPONSE";
            }
        }
        synchronized (this) {
            String finishedAt = Instant.now().toString();
            operation.verification = new Verification(operation.requestId, operationId, instanceId, path,
                    startedAt, finishedAt, httpStatus, accepted, echoedRequestId, echoedPath,
                    Map.copyOf(configuration), revision, status, error);
            logs.add(new DemoLogEntry(operation.requestId, finishedAt, "remediation_verification", path,
                    httpStatus, new DownstreamResponse(accepted, error.isEmpty() ? null : error, echoedPath),
                    error.isEmpty() ? null : error));
            if (logs.size() > 30) {
                logs.removeFirst();
            }
        }
    }

    /** 只读原回执；已退出而从未应用有本实例预留记录才可确认未应用。 */
    @Override
    public synchronized Receipt receipt(String operationId) {
        Operation operation = operations.get(operationId);
        if (operation == null) {
            return null;
        }
        ApplicationReceipt application = operation.application;
        boolean inFlight = inFlight(operationId);
        if (!inFlight && "NOT_STARTED".equals(application.status())) {
            application = new ApplicationReceipt(operationId, instanceId, "NOT_APPLIED",
                    application.beforeRevision(), application.afterRevision(), application.before(),
                    application.after(), null, "HANDLER_EXITED_BEFORE_APPLY");
        }
        return new Receipt(application, operation.verification, inFlight);
    }

    /** 去除在途标记，保留待补录证据。 */
    @Override
    public void exited(String operationId) {
        inFlightOperations.remove(operationId);
    }

    /** 仅服务层完整持久化后调用，失败操作由核查保留。 */
    @Override
    public synchronized void release(String operationId) {
        if (!inFlight(operationId)) {
            operations.remove(operationId);
        }
    }

    /** 在途查询不等待 HTTP 持有的环境锁。 */
    @Override
    public boolean inFlight(String operationId) {
        return inFlightOperations.contains(operationId);
    }

    /** 缺少受理前预留登记时拒绝直接写入。 */
    private Operation operation(String operationId) {
        Operation operation = operations.get(operationId);
        if (operation == null || !inFlight(operationId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "REMEDIATION_OPERATION_NOT_RESERVED");
        }
        return operation;
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
                    BoundedDemoHttp.send(http, request, Duration.ofSeconds(RemediationContract.HTTP_BUDGET_SECONDS),
                            RemediationContract.MAX_EVIDENCE_BYTES);
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
        String requestId = exchange.getRequestHeaders().getFirst("X-OrderBridge-Request-Id");
        send(exchange, 200, Map.of("accepted", true, "path", "/v2/orders",
                "requestId", requestId == null ? "manual" : requestId));
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
