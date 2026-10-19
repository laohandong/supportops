package io.supportops.remediation.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import io.supportops.remediation.dto.RemediationRequests;
import io.supportops.remediation.dto.RemediationRequests.Command;
import io.supportops.remediation.dto.RemediationRequests.LegacyExecute;
import io.supportops.remediation.service.RemediationService;
import io.supportops.remediation.service.support.RemediationJson;
import org.springframework.stereotype.Component;

/** 仅修复协议严格拒绝未知字段，不改变已有接口兼容。 */
@Component
public final class RemediationInput {
    private final ObjectMapper strict;
    private final Validator validator;
    private final RemediationService service;
    private final RemediationJson canonical;

    /** 创建专用白名单解析器。 */
    public RemediationInput(ObjectMapper mapper, Validator validator, RemediationService service, RemediationJson canonical) {
        strict = mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.validator = validator;
        this.service = service;
        this.canonical = canonical;
    }

    /** 有界正文与 Bean Validation；身份、目标、任意命令字段不能混入。 */
    public <T> T decode(JsonNode body, Class<T> type) {
        try {
            if (body == null || !body.isObject() || body.toString().length() > 16_384) {
                throw new IllegalArgumentException("INVALID_INPUT");
            }
            T request = strict.treeToValue(body, type);
            if (!validator.validate(request).isEmpty()) {
                throw new IllegalArgumentException("INVALID_INPUT");
            }
            return request;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("INVALID_INPUT", exception);
        }
    }

    /** 在解析树之前限制正文长度，避免将任意协议树公开为Swagger请求模型。 */
    public JsonNode parse(String body) {
        try {
            if (body == null || body.length() > 16_384) {
                throw new IllegalArgumentException("INVALID_INPUT");
            }
            return strict.readTree(body);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("INVALID_INPUT", exception);
        }
    }

    /** 白名单HTTP正文先有界解析，再转换为明确DTO。 */
    public <T> T decode(String body, Class<T> type) {
        return decode(parse(body), type);
    }

    /** 写请求先解码为原DTO，字段拒绝保存可查询回执；未知字段仅保存有界协议树的摘要。 */
    public <T> T decodeCommand(String body, Class<T> type, String kind, String target) {
        JsonNode parsed = parse(body);
        T request = null;
        try {
            if (parsed == null || !parsed.isObject()) {
                throw new IllegalArgumentException("INVALID_INPUT");
            }
            request = strict.treeToValue(parsed, type);
            if (!validator.validate(request).isEmpty()) {
                throw new IllegalArgumentException("INVALID_INPUT");
            }
            return request;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            String requestId = parsed == null ? null : parsed.path("requestId").asText(null);
            if (requestId != null && requestId.matches(RemediationRequests.UUID_PATTERN)) {
                String encoded;
                if (request instanceof LegacyExecute legacy) {
                    encoded = canonical.write(new Command(legacy.requestId(), legacy.expectedVersion(), legacy.planHash(), null));
                } else {
                    encoded = canonical.write(request == null ? parsed : request);
                }
                service.rejectInvalidInput(requestId, kind, target, RemediationJson.hash(encoded));
            }
            throw new IllegalArgumentException("INVALID_INPUT", exception);
        }
    }
}
