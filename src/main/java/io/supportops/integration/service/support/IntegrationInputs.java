package io.supportops.integration.service.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 统一校验标识与摘要，错误消息不包含请求载荷或秘密。 */
public final class IntegrationInputs {
    /** 工具类禁止实例化。 */
    private IntegrationInputs() {}
    /** 只接受标准 UUID，并统一小写序列化。 */
    public static String uuid(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) {
            throw error(400, "INVALID_RESOURCE_ID");
        }
        return UUID.fromString(value).toString();
    }
    /** 校验用于协议身份的稳定编码。 */
    public static String code(String value) {
        if (value == null || !value.matches("[a-z][a-z0-9_-]{0,63}")) {
            throw error(400, "INVALID_RESOURCE_CODE");
        }
        return value;
    }
    /** 校验文本长度以及孤立代理字符，不自动改变业务文本。 */
    public static String text(String value, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw error(400, "INVALID_INPUT");
        }
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) {
                    throw error(400, "INVALID_INPUT");
                }
            } else if (Character.isLowSurrogate(current)) {
                throw error(400, "INVALID_INPUT");
            }
        }
        return value;
    }
    /** 对 UTF-8 规范载荷计算 SHA-256。 */
    public static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("DIGEST_UNAVAILABLE", exception);
        }
    }
    /** 创建受控业务错误。 */
    public static ResponseStatusException error(int status, String code) {
        return new ResponseStatusException(HttpStatus.valueOf(status), code);
    }
}
