package io.supportops.user.vo;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
/** 可公开的账号信息，绝不包含密码摘要。 */
@Schema(description = "账号信息，不包含密码或凭据")
public record UserView(
        @Schema(description = "用户 UUID") String id,
        @Schema(description = "登录名") String username,
        @Schema(description = "ADMIN 管理员，USER 普通用户", allowableValues = {"ADMIN", "USER"}) String role,
        @Schema(description = "创建时间，ISO 8601") String createdAt) {
    /** 判断当前账号能否使用后台管理功能。 */
    @JsonIgnore
    public boolean isAdmin() {
        return "ADMIN".equals(role);
    }
}
