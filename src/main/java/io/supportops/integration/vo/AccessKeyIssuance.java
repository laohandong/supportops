package io.supportops.integration.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/** 明文仅在首次签发响应返回，固定请求重放只返回 key 状态。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "应用密钥首次签发结果；原文只能本次读取，原回执不回放秘密")
public record AccessKeyIssuance(@Schema(description = "签发或重放的公开状态") AccessKeyView key,
        @Schema(description = "一次性 Bearer 原文；重放为空，丢失时须撤销并重新签发") String secret) {
    /** 防止调试字符串回显访问密钥。 */
    @Override public String toString() { return "AccessKeyIssuance[redacted]"; }
}
