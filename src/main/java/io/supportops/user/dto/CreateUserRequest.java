package io.supportops.user.dto;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
/** 管理员创建账号的参数，登录请求不能修改角色。 */
@Schema(description = "新建账号")
public record CreateUserRequest(
        @NotBlank @Size(max = 32) @Schema(description = "唯一登录名，3–32 位字母、数字或下划线", requiredMode = Schema.RequiredMode.REQUIRED) String username,
        @NotBlank @Size(min = 12, max = 128) @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        @Schema(description = "初始密码，12–128 个字符", accessMode = Schema.AccessMode.WRITE_ONLY, requiredMode = Schema.RequiredMode.REQUIRED) String password,
        @NotBlank @Pattern(regexp = "ADMIN|USER") @Schema(description = "ADMIN 管理员，USER 普通用户", allowableValues = {"ADMIN", "USER"}, requiredMode = Schema.RequiredMode.REQUIRED) String role) {}
