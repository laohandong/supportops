package io.supportops.user.vo;
import io.swagger.v3.oas.annotations.media.Schema;
/** 首次启动是否需要创建管理员。 */
@Schema(description = "账号初始化状态")
public record SetupStatus(@Schema(description = "true 表示尚无账号，允许创建首个管理员") boolean setupRequired,
        @Schema(description = "企业部署需同时提供安装时生成的一次性初始化口令") boolean setupSecretRequired) {
    /** 本地初始化兼容原布尔状态。 */
    public SetupStatus(boolean setupRequired) { this(setupRequired, false); }
}
