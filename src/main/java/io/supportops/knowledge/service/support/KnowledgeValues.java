package io.supportops.knowledge.service.support;

import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/** 文档标识、摘要和可公开错误码的共同规则。 */
public final class KnowledgeValues {
    /** 工具类不可实例化。 */
    private KnowledgeValues() {}

    /** 创建无业务含义的稳定 UUID。 */
    public static String id() {
        return UUID.randomUUID().toString();
    }

    /** 返回 UTC ISO 8601 时间。 */
    public static String now() {
        return Instant.now().toString();
    }

    /** 对 UTF-8 字符串计算摘要。 */
    public static String hash(String value) {
        return hash(value.getBytes(StandardCharsets.UTF_8));
    }

    /** 对原始字节计算摘要。 */
    public static String hash(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException("HASH_UNAVAILABLE");
        }
    }

    /** 只公开由代码定义的错误码，屏蔽 SQL、正文、凭据和供应商响应。 */
    public static String error(Throwable error) {
        String message =
                error instanceof ResponseStatusException response
                        ? response.getReason()
                        : error.getMessage();
        return message != null && message.matches("[A-Z][A-Z0-9_]{2,99}")
                ? message
                : "KNOWLEDGE_PROCESSING_FAILED";
    }
}
