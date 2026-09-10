package io.supportops.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 登录只验证已有密码，不将新建账号的密码策略套用于历史凭据。 */
@Schema(description = "已有账号的登录凭据")
public record LoginRequest(
        @NotBlank @Size(max = 32)
        @Schema(description = "登录名，3–32 位 ASCII 字母、数字或下划线，不区分大小写", requiredMode = Schema.RequiredMode.REQUIRED) String username,
        @NotBlank @Size(max = 128) @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        @Schema(description = "已有账号密码，必填且最多 128 个字符；新建账号仍要求至少 12 个字符", accessMode = Schema.AccessMode.WRITE_ONLY, requiredMode = Schema.RequiredMode.REQUIRED) String password) {}
