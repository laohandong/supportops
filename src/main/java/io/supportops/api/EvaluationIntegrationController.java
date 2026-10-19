package io.supportops.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.net.URI;

/** 只返回部署者配置的外部平台入口，不建立跨系统登录或后台调用。 */
@RestController
@Tag(name="评测平台入口", description="仅管理员；来源 JSON 由人员显式导出与导入")
public final class EvaluationIntegrationController {
    private final String platformUrl;

    /** 校验展示链接，禁止脚本协议、凭据和非回环明文地址。 */
    public EvaluationIntegrationController(Environment environment) {
        String configured = environment.getProperty("supportops.evaluation-platform-url", "");
        if (configured.isBlank()) {
            platformUrl = null;
            return;
        }
        URI uri = URI.create(configured);
        boolean allowed = "https".equals(uri.getScheme()) || "http".equals(uri.getScheme())
                && ("127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost()));
        if (!allowed || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("EVALUATION_PLATFORM_URL_INVALID");
        }
        platformUrl = configured;
    }

    /** 未配置时明确为空，页面不猜测平台端口或转发来源正文。 */
    @GetMapping("/api/evaluation-integration")
    @Operation(summary="查看独立 AgentEval 入口", description="platformUrl 为空表示尚未配置平台链接")
    public Integration get() {
        return new Integration(platformUrl);
    }

    /** 公开链接不包含凭据。 */
    @Schema(description="独立平台导航，不代表服务可达或共享身份")
    public record Integration(@Schema(description="部署配置的 HTTPS 或回环 HTTP 地址；未配置为空") String platformUrl) {}
}
