package io.supportops.integration.vo;

/** 认证链独立的机器身份；不伪造人员账号。 */
public record ApplicationPrincipal(String applicationId, String keyId) {}
