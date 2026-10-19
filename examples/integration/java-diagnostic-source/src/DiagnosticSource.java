import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsServer;
import com.sun.net.httpserver.HttpsConfigurator;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.KeyStore;
import javax.net.ssl.SSLContext;
import javax.net.ssl.KeyManagerFactory;
import java.io.InputStream;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executors;

/** 独立 Java 21 合成诊断源，不依赖 SupportOps 或 OrderBridge 业务类。 */
public final class DiagnosticSource {
    private final String system;
    private final String environment;
    private final String product;
    private final String version;
    private final String token;
    private final String instance;
    /** 从启动环境读取固定身份和只读 Secret 文件，不提供公开默认秘密。 */
    private DiagnosticSource(Map<String, String> variables) throws IOException {
        system = code(variables.get("SOURCE_SYSTEM")); environment = code(variables.get("SOURCE_ENVIRONMENT"));
        product = code(variables.get("SOURCE_PRODUCT")); version = variables.get("SOURCE_VERSION");
        instance = code(variables.getOrDefault("SOURCE_INSTANCE", "sample-node"));
        if (version == null || !version.matches("[A-Za-z0-9._-]{1,30}")) { throw new IllegalArgumentException("SOURCE_VERSION_REQUIRED"); }
        token = Files.readString(Path.of(variables.get("SOURCE_SECRET_FILE"))).strip();
        if (!token.matches("[A-Za-z0-9_-]{43,128}")) { throw new IllegalArgumentException("SOURCE_SECRET_INVALID"); }
    }
    /** HTTP仅允许合成回环；显式TLS文件可在独立容器运行同协议的HTTPS源。 */
    public static void main(String[] arguments) throws Exception {
        DiagnosticSource source = new DiagnosticSource(System.getenv());
        int port = Integer.parseInt(System.getenv().getOrDefault("SOURCE_PORT", "0"));
        String address = System.getenv().getOrDefault("SOURCE_BIND_ADDRESS", "127.0.0.1");
        String tlsFile = System.getenv("SOURCE_TLS_KEYSTORE");
        HttpServer server;
        if (tlsFile != null) {
            char[] password = Files.readString(Path.of(System.getenv("SOURCE_TLS_PASSWORD_FILE"))).strip().toCharArray();
            KeyStore keys = KeyStore.getInstance("PKCS12");
            try (InputStream input = Files.newInputStream(Path.of(tlsFile))) { keys.load(input, password); }
            KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            managers.init(keys, password);
            SSLContext context = SSLContext.getInstance("TLS"); context.init(managers.getKeyManagers(), null, null);
            HttpsServer secure = HttpsServer.create(new InetSocketAddress(address, port), 0);
            secure.setHttpsConfigurator(new HttpsConfigurator(context)); server = secure;
        } else {
            if (!"127.0.0.1".equals(address)) { throw new IllegalArgumentException("SOURCE_HTTPS_REQUIRED"); }
            server = HttpServer.create(new InetSocketAddress(address, port), 0);
        }
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/v1/", source::handle);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
        server.start();
        System.out.println("{\"status\":\"READY\",\"port\":" + server.getAddress().getPort() + "}");
    }
    /** 固定四条 GET 路径；鉴权和范围检查在返回事实之前执行。 */
    private void handle(HttpExchange exchange) throws IOException {
        try (HttpExchange request = exchange) {
            String authorization = request.getRequestHeaders().getFirst("Authorization");
            if (authorization == null || !MessageDigest.isEqual(("Bearer " + token).getBytes(StandardCharsets.UTF_8),
                    authorization.getBytes(StandardCharsets.UTF_8))) { send(request, 401, "{\"error\":\"UNAUTHORIZED\"}"); return; }
            if (!"GET".equals(request.getRequestMethod())) { send(request, 405, "{\"error\":\"READ_ONLY\"}"); return; }
            String requestId = request.getRequestHeaders().getFirst("X-SupportOps-Request-Id");
            if (requestId == null || !requestId.matches("[0-9a-f-]{36}")) { send(request, 400, "{\"error\":\"REQUEST_ID_REQUIRED\"}"); return; }
            if ("/v1/recent-logs".equals(request.getRequestURI().getPath()) && !logsQuery(request.getRequestURI().getRawQuery())) {
                send(request, 400, "{\"error\":\"BOUNDED_LOG_WINDOW_REQUIRED\"}"); return;
            }
            String data = switch (request.getRequestURI().getPath()) {
                case "/v1/app-info" -> "{\"applicationName\":\"独立合成 " + system + "\",\"capabilities\":[\"APP_INFO\",\"EFFECTIVE_CONFIG\",\"RECENT_LOGS\",\"DOWNSTREAM_HEALTH\"]}";
                case "/v1/effective-config" -> "{\"items\":[{\"key\":\"sync.mode\",\"value\":\"readonly-sample\",\"redacted\":false,\"valueSource\":\"sample\"},{\"key\":\"api.token\",\"value\":null,\"redacted\":true,\"valueSource\":\"secret-file\"}]}";
                case "/v1/recent-logs" -> "{\"entries\":[{\"timestamp\":\"" + Instant.now() + "\",\"level\":\"WARN\",\"message\":\"" + system + " 合成同步依赖响应延迟，未写入业务数据\",\"traceId\":null}],\"truncated\":false}";
                case "/v1/downstream-health" -> "{\"dependencies\":[{\"name\":\"sample-erp\",\"status\":\"DOWN\",\"observedAt\":\"" + Instant.now() + "\",\"responseCode\":503,\"reason\":\"合成依赖不可用\"}]}";
                default -> null;
            };
            if (data == null) { send(request, 404, "{\"error\":\"CAPABILITY_NOT_FOUND\"}"); return; }
            String envelope = "{\"protocolVersion\":\"1.0\",\"requestId\":\"" + requestId + "\",\"target\":{\"systemCode\":\"" + system
                    + "\",\"environmentCode\":\"" + environment + "\",\"productKey\":\"" + product + "\",\"productVersion\":\"" + version
                    + "\"},\"observedAt\":\"" + Instant.now() + "\",\"source\":{\"kind\":\"APPLICATION\",\"instanceId\":\"" + instance + "\"},\"data\":" + data + "}";
            send(request, 200, envelope);
        }
    }
    /** 日志查询仅接受limit、from、to；最多200条和15分钟窗口，未知参数拒绝。 */
    private boolean logsQuery(String query) {
        try {
            Map<String,String> values = new java.util.HashMap<>();
            for (String item : query.split("&")) {
                String[] pair = item.split("=",2);
                if (pair.length != 2 || !java.util.Set.of("limit","from","to").contains(pair[0])
                        || values.putIfAbsent(pair[0],java.net.URLDecoder.decode(pair[1],StandardCharsets.UTF_8)) != null) { return false; }
            }
            int limit = Integer.parseInt(values.get("limit"));
            Instant from = Instant.parse(values.get("from")); Instant to = Instant.parse(values.get("to"));
            return limit >= 1 && limit <= 200 && from.isBefore(to) && java.time.Duration.between(from,to).toSeconds() <= 900;
        } catch (Exception exception) { return false; }
    }
    /** 控制身份字符集，禁止将任意输入拼入样例 JSON。 */
    private static String code(String value) {
        if (value == null || !value.matches("[a-z][a-z0-9_-]{0,63}")) { throw new IllegalArgumentException("SOURCE_IDENTITY_REQUIRED"); }
        return value;
    }
    /** 输出有 Content-Length 的有界 JSON，不记录请求秘密。 */
    private void send(HttpExchange exchange, int status, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
