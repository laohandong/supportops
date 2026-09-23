package io.supportops.feishu.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** 管理员为已有工作台账号签发一次性绑定码，不接受客户端指定平台身份。 */
@Schema(description = "飞书绑定码签发请求，仅管理员可用")
public record CreateFeishuBinding(
        @NotBlank @Pattern(regexp = "[0-9a-fA-F-]{36}")
        @Schema(description = "待绑定的已有工作台用户 UUID", requiredMode = Schema.RequiredMode.REQUIRED)
        String userId) {}
