package io.supportops.integration.service.support;

import io.supportops.integration.dto.ConnectorConfig;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.IDN;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.springframework.stereotype.Component;

/** 固定批准 IP 建立连接，TLS 仍验证原域名；不继承代理、重试或重定向。 */
@Component
@org.springframework.context.annotation.DependsOn("enterpriseTlsTrust")
public class PinnedHttpSource {
    private final AddressResolver resolver;
    /** 服务端固定的地址解析适配，不能由HTTP或模型指定。 */
    @FunctionalInterface
    public interface AddressResolver {
        /** 解析本次主机的全部地址，调用方仍须逐个核对批准清单。 */
        InetAddress[] resolve(String host) throws Exception;
    }
    /** 生产使用系统DNS，解析仍受有界执行器和预算保护。 */
    public PinnedHttpSource() { this(InetAddress::getAllByName); }
    /** 隔离验证可固定DNS变化；解析结果不能绕过实际连接的地址核对。 */
    public PinnedHttpSource(AddressResolver resolver) { this.resolver = resolver; }
    private static final int BODY_LIMIT = 1024 * 1024;
    private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("connector-deadline").factory());
    private static final ExecutorService DNS = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), Thread.ofPlatform().daemon().name("connector-dns-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());
    /** 能力固定映射，不允许模型指定路径。 */
    private static final Map<String, String> PATHS = Map.of("APP_INFO", "/v1/app-info", "EFFECTIVE_CONFIG", "/v1/effective-config",
            "RECENT_LOGS", "/v1/recent-logs", "DOWNSTREAM_HEALTH", "/v1/downstream-health");
    /** 校验地址和能力策略；解析结果在实际调用前再次固定并核对。 */
    public URI validate(ConnectorConfig config) {
        try {
            URI uri = URI.create(config.baseUrl());
            boolean https = "https".equals(uri.getScheme());
            boolean http = "http".equals(uri.getScheme()) && config.allowLocalHttp();
            if ((!https && !http) || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || uri.getPort() == 0 || uri.getPort() > 65535
                    || !uri.getRawPath().matches("/[A-Za-z0-9/_-]*|") || config.allowedAddresses() == null
                    || config.allowedAddresses().isEmpty() || config.allowedAddresses().size() > 16
                    || config.capabilities() == null || !config.capabilities().contains("APP_INFO")
                    || !PATHS.keySet().containsAll(config.capabilities()) || config.modelData() == null
                    || !config.capabilities().containsAll(config.modelData()) || config.baseUrl().length() > 1024) {
                throw new IllegalArgumentException();
            }
            for (String address : config.allowedAddresses()) {
                InetAddress parsed = literal(address);
                if (forbidden(parsed) || (parsed.isLoopbackAddress() && !config.allowLocalHttp())
                        || (config.allowLocalHttp() && !parsed.isLoopbackAddress())) {
                    throw new IllegalArgumentException();
                }
            }
            return uri;
        } catch (Exception exception) {
            throw IntegrationInputs.error(400, "INVALID_CONNECTOR_NETWORK_POLICY");
        }
    }
    /** 精确固定 DNS 结果中的首个地址，所有解析地址均须在批准清单内。 */
    public byte[] get(ConnectorConfig config, String capability, String secret, String requestId, String runId, String query) {
        long started = System.nanoTime();
        URI uri = validate(config);
        if (secret == null || !secret.matches("[!-~]{1,8192}")) {
            throw IntegrationInputs.error(503, "CREDENTIAL_INVALID");
        }
        if (!config.capabilities().contains(capability) || !PATHS.containsKey(capability)) {
            throw IntegrationInputs.error(403, "CAPABILITY_NOT_GRANTED");
        }
        String host = uri.getHost().replace("[", "").replace("]", "");
        int port = uri.getPort() < 0 ? ("https".equals(uri.getScheme()) ? 443 : 80) : uri.getPort();
        try {
            Set<String> allowed = new HashSet<>();
            for (String address : config.allowedAddresses()) {
                allowed.add(literal(address).getHostAddress());
            }
            InetAddress[] resolved = resolve(host);
            if (resolved.length == 0) {
                throw IntegrationInputs.error(502, "TARGET_DNS_FAILED");
            }
            for (InetAddress address : resolved) {
                if (forbidden(address) || !allowed.contains(address.getHostAddress())) {
                    throw IntegrationInputs.error(403, "TARGET_ADDRESS_NOT_APPROVED");
                }
            }
            try (Socket raw = new Socket()) {
                long remaining = Math.max(1, TimeUnit.SECONDS.toNanos(10) - (System.nanoTime() - started));
                ScheduledFuture<?> deadline = DEADLINES.schedule(() -> close(raw), remaining, TimeUnit.NANOSECONDS);
                try {
                    raw.connect(new InetSocketAddress(resolved[0], port), 3000);
                    raw.setSoTimeout(5000);
                    Socket connection = raw;
                    if ("https".equals(uri.getScheme())) {
                        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
                        SSLSocket tls = (SSLSocket) factory.createSocket(raw, host, port, true);
                        SSLParameters parameters = tls.getSSLParameters();
                        parameters.setEndpointIdentificationAlgorithm("HTTPS");
                        if (!host.contains(":" ) && !host.matches("[0-9.]+")) {
                            parameters.setServerNames(java.util.List.of(new SNIHostName(IDN.toASCII(host))));
                        }
                        tls.setSSLParameters(parameters);
                        tls.setSoTimeout(5000);
                        tls.startHandshake();
                        connection = tls;
                    }
                    try (Socket opened = connection) {
                        String path = uri.getRawPath().replaceAll("/+$", "") + PATHS.get(capability) + query;
                        String authority = host.contains(":") ? "[" + host + "]" : host;
                        String headers = "GET " + path + " HTTP/1.1\r\nHost: " + authority + ":" + port
                                + "\r\nAccept: application/json\r\nAccept-Encoding: identity\r\nConnection: close\r\n"
                                + "Authorization: Bearer " + secret + "\r\nX-SupportOps-Request-Id: " + IntegrationInputs.uuid(requestId)
                                + (runId == null ? "" : "\r\nX-SupportOps-Run-Id: " + IntegrationInputs.uuid(runId)) + "\r\n\r\n";
                        OutputStream output = opened.getOutputStream();
                        output.write(headers.getBytes(StandardCharsets.US_ASCII));
                        output.flush();
                        return body(opened.getInputStream());
                    }
                } finally {
                    deadline.cancel(false);
                }
            }
        } catch (org.springframework.web.server.ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw IntegrationInputs.error(502, "TARGET_CONNECTION_FAILED");
        }
    }
    /** DNS 也计入总预算；受限线程池避免失控解析占用无限线程。 */
    private InetAddress[] resolve(String host) {
        Future<InetAddress[]> lookup = DNS.submit(() -> resolver.resolve(host));
        try { return lookup.get(2, TimeUnit.SECONDS); }
        catch (InterruptedException exception) {
            lookup.cancel(true); Thread.currentThread().interrupt(); throw IntegrationInputs.error(502, "TARGET_DNS_INTERRUPTED");
        } catch (Exception exception) {
            lookup.cancel(true); throw IntegrationInputs.error(502, "TARGET_DNS_FAILED");
        }
    }
    /** 限制响应头、正文及编码；拒绝所有重定向和异常状态。 */
    private byte[] body(InputStream input) throws Exception {
        String first = line(input, 4096);
        if (!first.matches("HTTP/1\\.[01] [0-9]{3}.*")) {
            throw IntegrationInputs.error(502, "TARGET_HTTP_INVALID");
        }
        int status = Integer.parseInt(first.substring(9, 12));
        if (status != 200) {
            String code = status == 401 ? "TARGET_AUTHENTICATION_FAILED" : status == 403 ? "TARGET_PERMISSION_DENIED"
                    : status >= 300 && status < 400 ? "TARGET_REDIRECT_REJECTED" : "TARGET_HTTP_FAILED";
            throw IntegrationInputs.error(502, code);
        }
        Map<String, String> headers = new HashMap<>();
        int bytes = first.length();
        for (int count = 0; count < 100; count++) {
            String header = line(input, 4096);
            bytes += header.length() + 2;
            if (bytes > 16384) { throw IntegrationInputs.error(502, "TARGET_HEADERS_TOO_LARGE"); }
            if (header.isEmpty()) { break; }
            int separator = header.indexOf(':');
            if (separator < 1 || count == 99) { throw IntegrationInputs.error(502, "TARGET_HTTP_INVALID"); }
            String name = header.substring(0, separator).toLowerCase(Locale.ROOT);
            if (headers.putIfAbsent(name, header.substring(separator + 1).strip()) != null) {
                throw IntegrationInputs.error(502, "TARGET_HTTP_INVALID");
            }
        }
        if (!headers.getOrDefault("content-type", "").toLowerCase(Locale.ROOT).matches("application/json(;.*)?")
                || !headers.getOrDefault("content-encoding", "identity").equalsIgnoreCase("identity")) {
            throw IntegrationInputs.error(502, "TARGET_ENCODING_REJECTED");
        }
        String transfer = headers.get("transfer-encoding");
        String length = headers.get("content-length");
        if (transfer != null && (length != null || !transfer.equalsIgnoreCase("chunked"))) {
            throw IntegrationInputs.error(502, "TARGET_HTTP_INVALID");
        }
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        if (transfer != null) {
            while (true) {
                String size = line(input, 32);
                if (!size.matches("[0-9a-fA-F]{1,8}")) { throw IntegrationInputs.error(502, "TARGET_HTTP_INVALID"); }
                long chunk = Long.parseLong(size, 16);
                if (chunk == 0) {
                    if (!line(input, 2).isEmpty()) { throw IntegrationInputs.error(502, "TARGET_HTTP_INVALID"); }
                    break;
                }
                read(input, result, chunk);
                if (!line(input, 2).isEmpty()) { throw IntegrationInputs.error(502, "TARGET_HTTP_INVALID"); }
            }
        } else if (length != null) {
            if (!length.matches("[0-9]{1,9}")) { throw IntegrationInputs.error(502, "TARGET_HTTP_INVALID"); }
            read(input, result, Long.parseLong(length));
        } else {
            int value;
            while ((value = input.read()) >= 0) {
                if (result.size() >= BODY_LIMIT) { throw IntegrationInputs.error(502, "TARGET_BODY_TOO_LARGE"); }
                result.write(value);
            }
        }
        return result.toByteArray();
    }
    /** 按声明长度读取并检测提前关闭及正文上限。 */
    private void read(InputStream input, ByteArrayOutputStream output, long length) throws Exception {
        if (length < 0 || length + output.size() > BODY_LIMIT) { throw IntegrationInputs.error(502, "TARGET_BODY_TOO_LARGE"); }
        byte[] buffer = new byte[8192];
        long remaining = length;
        while (remaining > 0) {
            int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (count < 0) { throw IntegrationInputs.error(502, "TARGET_BODY_INCOMPLETE"); }
            output.write(buffer, 0, count);
            remaining -= count;
        }
    }
    /** 只接受 CRLF 行，防止宽松解析产生不一致。 */
    private String line(InputStream input, int maximum) throws Exception {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        while (result.size() <= maximum) {
            int value = input.read();
            if (value < 0) { throw IntegrationInputs.error(502, "TARGET_HTTP_INCOMPLETE"); }
            if (value == '\r') {
                if (input.read() != '\n') { throw IntegrationInputs.error(502, "TARGET_HTTP_INVALID"); }
                return result.toString(StandardCharsets.US_ASCII);
            }
            if (value < 32 || value > 126) { throw IntegrationInputs.error(502, "TARGET_HTTP_INVALID"); }
            result.write(value);
        }
        throw IntegrationInputs.error(502, "TARGET_HEADERS_TOO_LARGE");
    }
    /** 白名单项必须是 IP 字面量，不能再次发生 DNS 解析。 */
    private InetAddress literal(String address) throws Exception {
        if (address == null || (!address.matches("[0-9a-fA-F:]+") && !address.matches("([0-9]{1,3}\\.){3}[0-9]{1,3}"))) {
            throw new IllegalArgumentException();
        }
        if (!address.contains(":")) {
            String[] parts = address.split("\\.");
            if (parts.length != 4) { throw new IllegalArgumentException(); }
            for (String part : parts) {
                if (Integer.parseInt(part) > 255 || (part.length() > 1 && part.startsWith("0"))) { throw new IllegalArgumentException(); }
            }
        }
        return InetAddress.getByName(address);
    }
    /** 即使管理员登记也拒绝链路本地、元数据和不可路由目标。 */
    private boolean forbidden(InetAddress address) {
        return address.isAnyLocalAddress() || address.isLinkLocalAddress() || address.isMulticastAddress()
                || Set.of("100.100.100.200", "168.63.129.16").contains(address.getHostAddress());
    }
    /** 总预算或取消时关闭底层连接；关闭异常不覆盖原失败。 */
    private static void close(Socket socket) {
        try { socket.close(); } catch (Exception ignored) { /* 连接已关闭时保留原执行结果。 */ }
    }
}
