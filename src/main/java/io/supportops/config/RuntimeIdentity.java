package io.supportops.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.supportops.config.vo.RuntimeView;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.jar.JarFile;

/** 捕获真实运行文件、OS 进程身份和 Spring 就绪事件；不访问业务存储。 */
@Component
public final class RuntimeIdentity {
    private final ObjectMapper json;
    private final String runtimeId;
    private final String startedAt;
    private final String jarSha256;
    private final String applicationVersion;
    private final Path receipt;
    private volatile String phase = "STARTING";
    private volatile String readyAt;
    private volatile int port;

    /** 只接受本机 JVM 系统属性中的回执路径，并核对当前实际 classpath。 */
    public RuntimeIdentity(ObjectMapper json) throws Exception { this(json, false); }

    /** 企业直接启动也生成真实运行UUID，供API、guard及异常退出证据共同使用。 */
    @org.springframework.beans.factory.annotation.Autowired
    public RuntimeIdentity(ObjectMapper json,
            @org.springframework.beans.factory.annotation.Value("${supportops.enterprise.enabled:false}") boolean enterprise) throws Exception {
        this.json = json;
        this.runtimeId = System.getProperty("supportops.runtime.id", enterprise ? java.util.UUID.randomUUID().toString() : "unknown");
        this.startedAt = ProcessHandle.current().info().startInstant().map(Instant::toString).orElse(null);
        String classpath = System.getProperty("java.class.path", "");
        // 测试和 IDE 使用多个 classpath 条目；只有单一实际 JAR 才可声明文件身份。
        Path jar = classpath.isBlank() || classpath.contains(File.pathSeparator)
                ? null : Path.of(classpath).toAbsolutePath().normalize();
        String digest = "unknown";
        String version = "unknown";
        if (jar != null && Files.isRegularFile(jar) && classpath.endsWith(".jar")) {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(jar)) {
                byte[] buffer = new byte[65536];
                int length;
                while ((length = input.read(buffer)) != -1) {
                    hash.update(buffer, 0, length);
                }
            }
            digest = HexFormat.of().formatHex(hash.digest());
            try (JarFile archive = new JarFile(jar.toFile())) {
                String value = archive.getManifest().getMainAttributes().getValue("Implementation-Version");
                version = value == null ? "unknown" : value;
            }
        }
        this.jarSha256 = digest;
        this.applicationVersion = version;
        String receiptFile = System.getProperty("supportops.runtime.receipt");
        this.receipt = receiptFile == null ? null : Path.of(receiptFile).toAbsolutePath().normalize();
        if (receipt != null && (jar == null || !runtimeId.matches("[0-9a-f-]{36}")
                || !jar.equals(Path.of(System.getProperty("supportops.runtime.jar", "")).toAbsolutePath().normalize())
                || !receipt.getParent().equals(jar.getParent()) || !receipt.getFileName().toString().equals("ready.json"))) {
            throw new IllegalStateException("RUNTIME_RECEIPT_IDENTITY_INVALID");
        }
    }

    /** 端口以 WebServer 实际绑定值为准，支持显式零端口自动分配。 */
    @EventListener
    public void bound(WebServerInitializedEvent event) {
        this.port = event.getWebServer().getPort();
    }

    /** 业务初始化完成后才发布 READY；写入失败使受控启动失败，不能伪造就绪。 */
    @EventListener
    public synchronized void ready(ApplicationReadyEvent event) throws Exception {
        this.readyAt = Instant.now().toString();
        this.phase = "READY";
        writeReceipt();
    }

    /** 正常关闭也保留就绪时间；启动器仍独立确认 OS 退出事实。 */
    @EventListener
    public synchronized void stopped(ContextClosedEvent event) throws Exception {
        this.phase = "STOPPED";
        writeReceipt();
    }

    /** 返回无秘密的不可变运行身份；源码修订未嵌入时明确未知。 */
    public RuntimeView view() {
        return new RuntimeView(1, runtimeId, ProcessHandle.current().pid(), startedAt, jarSha256,
                applicationVersion, "unknown", "unknown", phase, readyAt, port);
    }

    /** 在运行副本同目录原子替换回执，网页不能修改路径或运行身份。 */
    private void writeReceipt() throws Exception {
        if (receipt == null) {
            return;
        }
        Path temporary = Files.createTempFile(receipt.getParent(), ".receipt-", ".tmp");
        try {
            Files.writeString(temporary, json.writeValueAsString(view()));
            Files.move(temporary, receipt, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
