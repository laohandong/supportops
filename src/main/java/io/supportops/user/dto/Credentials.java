package io.supportops.user.dto;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
/** 首次创建管理员的凭据；密码只允许写入，禁止记录请求正文。 */
@Schema(description = "首次创建管理员凭据")
public record Credentials(
        @NotBlank @Size(max = 32) @Schema(description = "登录名，3–32 位 ASCII 字母、数字或下划线，不区分大小写", requiredMode = Schema.RequiredMode.REQUIRED) String username,
        @NotBlank @Size(min = 12, max = 128) @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        @Schema(description = "密码，12–128 个字符", accessMode = Schema.AccessMode.WRITE_ONLY, requiredMode = Schema.RequiredMode.REQUIRED) String password) {}
