package io.supportops.api.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Validator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import java.util.HexFormat;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** 新命令纯序列化工具；键排序，数组保序，正文NFC、换行统一并去首尾空白。 */
@Component
public final class FixedCommandJson {
    private final ObjectMapper strict;
    private final Validator validator;

    /** 白名单解析器不改变旧接口或冻结包的Jackson行为。 */
    public FixedCommandJson(ObjectMapper mapper, Validator validator) {
        this.strict = mapper.copy().disable(com.fasterxml.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.validator = validator;
    }

    /** 解析树前限制UTF-8体积；拒绝未知字段、重复属性、枚举和超限正文。 */
    public <T> T decode(String body, Class<T> type) {
        if (body == null || body.getBytes(StandardCharsets.UTF_8).length > 262144) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "COMMAND_BODY_TOO_LARGE");
        }
        try {
            JsonNode tree = strict.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(body);
            if (tree == null || !tree.isObject()) {
                throw new IllegalArgumentException("INVALID_INPUT");
            }
            T value = strict.treeToValue(canonical(tree), type);
            if (!validator.validate(value).isEmpty()) {
                throw new IllegalArgumentException("INVALID_INPUT");
            }
            return value;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("INVALID_INPUT");
        }
    }

    /** 明确DTO及受限协议树序列化，不保存被拒绝的原敏感正文。 */
    public String write(Object value) {
        try {
            return strict.writeValueAsString(canonical(strict.valueToTree(value)));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("COMMAND_JSON_INVALID");
        }
    }

    /** 读取持久化类型，损坏数据不包装成成功。 */
    public <T> T read(String source, Class<T> type) {
        try {
            return strict.readValue(source, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("COMMAND_JSON_INVALID");
        }
    }

    /** 方法、动作和目标参与摘要，身份由请求表命名空间隔离。 */
    public String digest(String kind, String target, Object body) {
        return hash("POST\n" + kind + "\n" + target + "\n" + write(body));
    }

    /** 小型规范JSON及部署命名空间按UTF-8计算SHA256，不依赖业务模块。 */
    public static String hash(String source) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("COMMAND_HASH_UNAVAILABLE");
        }
    }

    /** 解析失败时仅提取有界请求身份及摘要，无法识别的正文不建立业务回执。 */
    public Rejected rejected(String body, String kind, String target) {
        if (body == null || body.getBytes(StandardCharsets.UTF_8).length > 262144) {
            return null;
        }
        try {
            JsonNode tree = strict.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(body);
            String id = tree == null ? null : tree.path("requestId").asText(null);
            if (id == null || !id.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
                return null;
            }
            return new Rejected(id, digest(kind, target, tree));
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    /** 拒绝回执只携带身份和摘要。 */
    public record Rejected(String requestId, String digest) {}

    /** 只为拒绝回执提取有界目标键；正文仍由严格DTO解析。 */
    public String field(String body, String name, String fallback) {
        if (body == null || body.getBytes(StandardCharsets.UTF_8).length > 262144) {
            return fallback;
        }
        try {
            JsonNode tree = strict.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(body);
            String value = tree == null ? null : tree.path(name).asText(null);
            if (value == null || value.length() > 80) {
                return fallback;
            }
            boolean validTarget = "sourceRunId".equals(name)
                    ? value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
                    : value.matches("[a-z0-9][a-z0-9-]{0,79}");
            return validTarget ? value : fallback;
        } catch (JsonProcessingException invalid) {
            return fallback;
        }
    }

    /** 字符串规范化，排序属性；数组顺序参与业务语义。 */
    private JsonNode canonical(JsonNode node) {
        if (node.isTextual()) {
            String text = node.textValue().replace("\r\n", "\n").replace('\r', '\n').replaceAll("^[ \\t\\n\\r]+|[ \\t\\n\\r]+$", "");
            return strict.getNodeFactory().textNode(Normalizer.normalize(text, Normalizer.Form.NFC));
        }
        if (node.isArray()) {
            ArrayNode result = strict.createArrayNode();
            for (JsonNode item : node) {
                result.add(canonical(item));
            }
            return result;
        }
        if (node.isObject()) {
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            fields.forEachRemaining(entry -> sorted.put(entry.getKey(), canonical(entry.getValue())));
            ObjectNode result = strict.createObjectNode();
            sorted.forEach(result::set);
            return result;
        }
        return node;
    }
}
