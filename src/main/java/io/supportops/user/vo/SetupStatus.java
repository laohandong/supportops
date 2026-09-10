package io.supportops.user.vo;
import io.swagger.v3.oas.annotations.media.Schema;
/** 首次启动是否需要创建管理员。 */
@Schema(description = "账号初始化状态")
public record SetupStatus(@Schema(description = "true 表示尚无账号，允许创建首个管理员") boolean setupRequired) {}
