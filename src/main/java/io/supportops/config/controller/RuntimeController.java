package io.supportops.config.controller;

import io.supportops.config.RuntimeIdentity;
import io.supportops.config.vo.RuntimeView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 复用默认管理员边界提供只读运行事实，不改变客户工作区状态接口。 */
@RestController
@Tag(name = "运行维护", description = "仅管理员可读的实际程序身份。")
public final class RuntimeController {
    private final RuntimeIdentity identity;

    /** 注入当前 JVM 的实际运行身份。 */
    public RuntimeController(RuntimeIdentity identity) {
        this.identity = identity;
    }

    /** 返回真实就绪状态及程序摘要，响应不包含本机私密配置。 */
    @GetMapping("/api/ops/runtime")
    @Operation(summary = "查询实际运行身份", description = "只读；不探测模型，不触发迁移。摘要来自实际运行文件。")
    @ApiResponse(responseCode = "200", description = "实际 JVM 身份。",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RuntimeView.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED：未登录。", content = @Content)
    @ApiResponse(responseCode = "403", description = "ADMIN_REQUIRED：需要管理员。", content = @Content)
    public RuntimeView runtime() {
        return identity.view();
    }
}
