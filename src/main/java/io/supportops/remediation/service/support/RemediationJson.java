package io.supportops.remediation.service.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.supportops.remediation.constant.RemediationContract;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 固定字段与 Map 键顺序，拒绝超限证据，不保存服务商或凭据原文。 */
@Component
public final class RemediationJson {
    private final JsonMapper json = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

    /** 将有明确类型的契约规范化为 JSON，并检查 UTF8 字节预算。 */
    public String write(Object value) {
        try {
            String encoded = json.writeValueAsString(value);
            if (encoded.getBytes(StandardCharsets.UTF_8).length > RemediationContract.MAX_EVIDENCE_BYTES) {
                throw new IllegalArgumentException("REMEDIATION_EVIDENCE_TOO_LARGE");
            }
            return encoded;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("REMEDIATION_EVIDENCE_INVALID", exception);
        }
    }

    /** 从持久化快照恢复指定契约；损坏证据不猜测成功。 */
    public <T> T read(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("REMEDIATION_EVIDENCE_INVALID", exception);
        }
    }

    /** 返回 UTF8 内容 SHA256。 */
    public static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA256_UNAVAILABLE", exception);
        }
    }
}
