package io.supportops.source.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.supportops.api.support.FixedCommandJson;
import io.supportops.source.vo.SourcePackage;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/** 两侧独立持有的版本化编解码实现；不以摘要证明来源真实性。 */
@Component
public final class SourcePackageCodec {
    private final FixedCommandJson json;
    private final ObjectMapper mapper;

    /** 注入严格JSON工具，复用固定规范编码，不读取任何外部系统。 */
    public SourcePackageCodec(FixedCommandJson json, ObjectMapper mapper) {
        this.json = json;
        this.mapper = mapper;
    }

    /** 对除摘要字段外的全部白名单字段计算规范身份。 */
    public String digest(SourcePackage value) {
        ObjectNode node = mapper.valueToTree(value);
        node.remove("contentSha256");
        return FixedCommandJson.hash(json.write(node));
    }

    /** 校验时间、128KiB上限与完整性；可信等级由接收审计另行记录。 */
    public void validate(SourcePackage value) {
        if (json.write(value).getBytes(StandardCharsets.UTF_8).length > 131072) {
            throw new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE, "SOURCE_PACKAGE_TOO_LARGE");
        }
        try {
            Instant.parse(value.exportedAt());
            Instant.parse(value.redactionConfirmedAt());
        } catch (RuntimeException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SOURCE_TIME_INVALID");
        }
        if (!digest(value).equals(value.contentSha256())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "SOURCE_DIGEST_MISMATCH");
        }
    }
}
