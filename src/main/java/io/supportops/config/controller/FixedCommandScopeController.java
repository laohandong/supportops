package io.supportops.config.controller;

import io.supportops.api.support.FixedCommandJson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 登录后仅返回部署存储命名空间摘要，用于浏览器跨重启固定请求隔离。 */
@RestController
@Tag(name = "账号会话")
public final class FixedCommandScopeController {
    private final String scope;

    /** 数据库连接仅参与不可逆摘要，不向浏览器公开路径、账号或连接字符串。 */
    public FixedCommandScopeController(Environment environment) {
        this.scope = FixedCommandJson.hash("command-scope-v1\n" + environment.getProperty("spring.datasource.url", "unconfigured")
                + "\n" + environment.getProperty("spring.datasource.username", ""));
    }

    /** 来源只读，账号由认证过滤器验证；不触发迁移、模型或存储写入。 */
    @GetMapping("/api/auth/command-scope")
    @Operation(summary = "固定命令部署命名空间", description = "登录可读，仅返回数据库部署摘要；重启保持稳定，换存储目标后隔离恢复信息")
    @ApiResponse(responseCode = "200", description = "固定命令部署摘要", content = @Content(schema = @Schema(implementation = Scope.class)))
    @ApiResponse(responseCode = "401", description = "未登录，原恢复命令继续保留")
    public Scope scope() {
        return new Scope(scope);
    }

    /** 不含原始部署配置。 */
    @Schema(name = "FixedCommandScope", description = "登录后的不可逆部署摘要，跨重启固定请求恢复使用")
    public record Scope(@Schema(description = "部署存储命名空间SHA256") String scope) {}
}
