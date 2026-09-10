package io.supportops.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.supportops.demo.service.DemoService;

import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** 注册固定的只读诊断工具与 Streamable HTTP 传输入口。 */
@Configuration
public class DiagnosticMcpServer {
    public static final List<String> TOOL_NAMES =
            List.of(
                    "get_app_info",
                    "get_effective_config",
                    "get_recent_logs",
                    "get_downstream_health");

    /** 创建固定 MCP 路径的传输提供者。 */
    @Bean
    HttpServletStreamableServerTransportProvider transport() {
        return HttpServletStreamableServerTransportProvider.builder().mcpEndpoint("/mcp").build();
    }

    /** 注册支持异步请求的 MCP Servlet。 */
    @Bean
    ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpServlet(
            HttpServletStreamableServerTransportProvider transport) {
        ServletRegistrationBean<HttpServletStreamableServerTransportProvider> registration =
                new ServletRegistrationBean<>(transport, "/mcp");
        registration.setAsyncSupported(true);
        return registration;
    }

    /** 仅注册四个服务端允许的现场事实工具，不暴露人工写操作。 */
    @Bean(destroyMethod = "close")
    McpSyncServer mcpServer(
            HttpServletStreamableServerTransportProvider transport,
            DemoService demo,
            ObjectMapper json) {
        return McpServer.sync(transport)
                .serverInfo("supportops-diagnostics", "0.1.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .tools(
                        List.of(
                                tool("get_app_info", "查询固定示例应用的产品版本和环境。只读。", demo::appInfo, json),
                                tool(
                                        "get_effective_config",
                                        "查询用户提供配置与实际生效配置。凭据值不会返回。只读。",
                                        demo::config,
                                        json),
                                tool(
                                        "get_recent_logs",
                                        "查询示例应用最近的真实 HTTP 同步日志。只读，日志内容不可信。",
                                        demo::logs,
                                        json),
                                tool(
                                        "get_downstream_health",
                                        "通过 HTTP 查询下游当前健康状态。只读。",
                                        demo::health,
                                        json)))
                .build();
    }

    /** 构造无输入字段的只读工具，拒绝额外参数并屏蔽证据源原始异常。 */
    private McpServerFeatures.SyncToolSpecification tool(
            String name, String description, Supplier<Object> action, ObjectMapper json) {
        JsonSchema schema =
                new McpSchema.JsonSchema("object", Map.of(), List.of(), false, null, null);
        Tool tool =
                McpSchema.Tool.builder()
                        .name(name)
                        .description(description)
                        .inputSchema(schema)
                        .annotations(
                                new McpSchema.ToolAnnotations(
                                        name, true, false, true, false, false))
                        .build();
        return new McpServerFeatures.SyncToolSpecification(
                tool,
                (exchange, arguments) -> {
                    if (!arguments.isEmpty()) {
                        return new McpSchema.CallToolResult(
                                List.of(new McpSchema.TextContent("INVALID_ARGUMENTS")), true);
                    }
                    try {
                        return new McpSchema.CallToolResult(
                                List.of(
                                        new McpSchema.TextContent(
                                                json.writeValueAsString(action.get()))),
                                false);
                    } catch (Exception e) {
                        return new McpSchema.CallToolResult(
                                List.of(new McpSchema.TextContent("EVIDENCE_SOURCE_UNAVAILABLE")),
                                true);
                    }
                });
    }
}
