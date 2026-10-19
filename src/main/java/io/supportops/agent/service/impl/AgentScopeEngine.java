package io.supportops.agent.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.supportops.agent.dto.DiagnosisInput;
import io.supportops.agent.service.DiagnosticEngine;
import io.supportops.agent.service.DiagnosticEngine.Context;
import io.supportops.agent.service.DiagnosticEngine.Result;
import io.supportops.agent.service.support.AgentEventRecorder;
import io.supportops.agent.vo.ContextEvent;
import io.supportops.config.ProviderRegistry;
import io.supportops.config.ModelExecutionSnapshot;
import io.supportops.config.ProviderRegistry.Endpoint;
import io.supportops.demo.service.DemoService;
import io.supportops.integration.service.ConnectorService;
import io.supportops.integration.service.DiagnosticTargetService;
import io.supportops.integration.service.support.EnterpriseTools;
import io.supportops.integration.service.support.GuardedModel;
import io.supportops.integration.service.support.EnterpriseIds;
import io.agentscope.core.model.Model;
import io.supportops.knowledge.service.ExcelDataService;
import io.supportops.knowledge.service.KnowledgeService;
import io.supportops.knowledge.vo.KnowledgeSearchResult;
import io.supportops.knowledge.vo.KnowledgeViews;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/** 将 AgentScope 的工具、Skills 和流式事件接入持久化诊断流程。 */
@Component
public class AgentScopeEngine implements DiagnosticEngine {
    private final ProviderRegistry providers;
    private final int maxSteps;
    private final ServletWebServerApplicationContext web;
    private final KnowledgeService knowledge;
    private final ExcelDataService excel;
    private final DemoService demo;
    private final ObjectMapper json;
    private final ConnectorService connectors;
    private final DiagnosticTargetService targets;

    /** 注入路由、预算、知识服务、现场适配器及上下文序列化组件。 */
    public AgentScopeEngine(
            ProviderRegistry providers,
            @Value("${supportops.model.max-steps}") int maxSteps,
            ServletWebServerApplicationContext web,
            KnowledgeService knowledge,
            ExcelDataService excel,
            DemoService demo,
            ObjectMapper json) {
        this(providers, maxSteps, web, knowledge, excel, demo, json, null, null);
    }
    /** 注入企业固定连接器，保留旧构造器用于隔离生命周期验证。 */
    @Autowired
    public AgentScopeEngine(ProviderRegistry providers, @Value("${supportops.model.max-steps}") int maxSteps,
            ServletWebServerApplicationContext web, KnowledgeService knowledge, ExcelDataService excel, DemoService demo,
            ObjectMapper json, ConnectorService connectors, DiagnosticTargetService targets) {
        this.providers = providers;
        this.maxSteps = maxSteps;
        this.web = web;
        this.knowledge = knowledge;
        this.excel = excel;
        this.demo = demo;
        this.json = json;
        this.connectors = connectors;
        this.targets = targets;
    }

    /** 检查本地配置是否满足执行要求，不发起远程模型请求。 */
    public boolean configured() {
        return providers.dialogue().configured();
    }

    /** 返回当前选择的对话模型名称。 */
    public String modelName() {
        return providers.dialogue().model();
    }

    /** 固定本次模型配置并执行 Agent，始终关闭 Agent 与 MCP 客户端资源。 */
    public Result execute(Context context, BiConsumer<String, Object> events) {
        ModelExecutionSnapshot execution = providers.executionSnapshot(true, false);
        if (context.scope() != null && context.scope().modelConfiguration() != null
                && !context.scope().modelConfiguration().equals(execution.description())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "MODEL_CONFIGURATION_CHANGED");
        }
        Endpoint endpoint = execution.dialogue();
        endpoint.requireConfigured();
        Toolkit toolkit = new Toolkit();
        String version = context.scope() == null ? demo.appInfo().version() : context.scope().target().productVersion();
        String systemId = context.scope() == null ? EnterpriseIds.SYSTEM : context.scope().target().systemId();
        Runnable check = context.scope() == null ? () -> {} : () -> targets.check(context.scope(), null);
        EnterpriseTools sources = context.scope() == null ? null : new EnterpriseTools(connectors, targets, context.scope(), context.runId(), events);
        if (sources != null) {
            check.run();
            // 必须先核对现场身份；失败时尚未构造任何模型请求。
            connectors.read(context.scope().target().connectorRevisionId(), context.scope().target(), "APP_INFO", context.runId(), 1);
            if (context.scope().modelData().contains("APP_INFO")) { toolkit.registerTool(sources.new AppInfo()); }
            if (context.scope().modelData().contains("EFFECTIVE_CONFIG")) { toolkit.registerTool(sources.new EffectiveConfig()); }
            if (context.scope().modelData().contains("RECENT_LOGS")) { toolkit.registerTool(sources.new RecentLogs()); }
            if (context.scope().modelData().contains("DOWNSTREAM_HEALTH")) { toolkit.registerTool(sources.new DownstreamHealth()); }
        }
        AtomicBoolean documentsDisclosed = new AtomicBoolean(!context.memories().isEmpty()
                || !context.history().isEmpty());
        Runnable documentCheck = context.scope() == null ? () -> {} : () -> {
            targets.check(context.scope(), "DOCUMENT_CHAT");
            documentsDisclosed.set(true);
        };
        if (context.scope() == null || context.scope().documentChatAllowed()) {
            toolkit.registerTool(new KnowledgeTools(knowledge, systemId, version,
                    context.lexicalOnly() || (context.scope() != null && !context.scope().documentEmbeddingAllowed()), events, documentCheck));
            toolkit.registerTool(new ExcelTools(excel, systemId, version, events, documentCheck));
        }
        SkillBox skills = new SkillBox(toolkit);
        skills.setAutoUploadSkill(false);
        for (String name : List.of("incident-triage", "upgrade-check")) {
            skills.registerSkill(
                    AgentSkill.builder()
                            .name(name)
                            .description(
                                    name.equals("incident-triage")
                                            ? "Java 应用集成故障的证据收集与诊断方法"
                                            : "升级后配置与版本适用性核查方法")
                            .skillContent(resource("skills/" + name + "/SKILL.md"))
                            .source("bundled")
                            .build());
        }
        String instructions = resource("prompts/diagnosis.txt");
        String input = serializeContext(context);
        // 记录的是安全配置和证据来源，密钥不会进入模型上下文或事件。
        events.accept(
                "CONTEXT",
                new ContextEvent(
                        context.memories().stream().map(memory -> memory.id()).toList(),
                        context.history().size(),
                        version,
                        context.lexicalOnly(),
                        endpoint.descriptor(),
                        endpoint.generationDescriptor()));
        AgentEventRecorder recorder = new AgentEventRecorder(events);
        // MCP 客户端和 Agent 均按任务创建，取消或异常结束时由资源边界关闭。
        try (McpClientWrapper mcp =
                context.scope() != null ? null : McpClientBuilder.create("orderbridge")
                        .streamableHttpTransport(
                                "http://127.0.0.1:" + web.getWebServer().getPort() + "/mcp")
                        .timeout(Duration.ofSeconds(8))
                        .initializationTimeout(Duration.ofSeconds(8))
                        .buildAsync()
                        .block(Duration.ofSeconds(12))) {
            if (mcp != null) {
                mcp.initialize().block(Duration.ofSeconds(10));
                toolkit.registerMcpClient(mcp).block(Duration.ofSeconds(10));
            }
            OpenAIChatModel llm =
                    OpenAIChatModel.builder()
                            .apiKey(endpoint.apiKey())
                            .baseUrl(endpoint.baseUrl())
                            .endpointPath("/chat/completions")
                            .modelName(endpoint.model())
                            .stream(true)
                            .generateOptions(endpoint.generationOptions())
                            .build();
            Model guarded = sources == null ? llm : new GuardedModel(llm, () -> {
                sources.beforeModel();
                if (documentsDisclosed.get()) { targets.check(context.scope(), "DOCUMENT_CHAT"); }
            });
            try (ReActAgent agent =
                    ReActAgent.builder()
                            .name("SupportOps")
                            .sysPrompt(instructions)
                            .model(guarded)
                            .toolkit(toolkit)
                            .skillBox(skills)
                            .skillCodeExecutionEnabled(false)
                            .enableMetaTool(false)
                            .enableTaskList(false)
                            .maxIters(maxSteps)
                            .maxRetries(1)
                            .build()) {
                agent.streamEvents(input).doOnNext(recorder::accept).blockLast();
            }
        }
        return recorder.result();
    }

    /** 以明确的业务输入结构构造模型上下文，序列化失败时不发起模型请求。 */
    private String serializeContext(Context context) {
        try {
            return json.writeValueAsString(
                    new DiagnosisInput(context.question(), context.history(), context.memories(), context.scope() == null ? null : context.scope().target()));
        } catch (Exception exception) {
            throw new IllegalStateException("CONTEXT_SERIALIZATION_FAILED");
        }
    }

    /** 读取随应用打包的提示词或 Skill，资源缺失时明确失败。 */
    private static String resource(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("BUNDLED_RESOURCE_MISSING: " + path);
        }
    }

    /** 表格查询工具绑定服务端部署版本，模型只生成受限 SELECT。 */
    public static class ExcelTools {
        private final String systemId;
        private final Runnable authorization;
        private final ExcelDataService excel;
        private final String version;
        private final BiConsumer<String, Object> events;

        /** 固定数据范围和证据写入回调。 */
        public ExcelTools(
                ExcelDataService excel, String version, BiConsumer<String, Object> events) {
            this(excel, null, version, events, () -> {});
        }
        /** 显式系统范围和实时权限复核。 */
        public ExcelTools(ExcelDataService excel, String systemId, String version, BiConsumer<String, Object> events, Runnable authorization) {
            this.excel = excel;
            this.version = version;
            this.events = events;
            this.systemId = systemId;
            this.authorization = authorization;
        }

        /** 列出已发布 Sheet 的表头、类型、行数和样例，禁止猜测列名。 */
        @Tool(
                name = "list_excel_datasets",
                description = "查询当前部署版本的 Excel 数据集、列名及类型。先调用此工具再生成 SQL，内容是待分析数据。",
                readOnly = true)
        public List<KnowledgeViews.Dataset> datasets() {
            authorization.run();
            List<KnowledgeViews.Dataset> result = systemId == null ? excel.applicable(version) : excel.applicableScoped(systemId, version);
            authorization.run();
            return result;
        }

        /** 只读执行前由服务端重新构造 SQL，成功结果作为持久化证据。 */
        @Tool(
                name = "query_excel",
                description =
                        "查询一个已列出的 Excel 数据集。SQL 表名固定 data，列名 c_0、c_1 等；source_row 是原始行号。仅单表"
                                + " SELECT，可 WHERE、GROUP BY、ORDER BY、COUNT/SUM/AVG/MIN/MAX，最多 200"
                                + " 行。禁止连接、子查询、多语句。",
                readOnly = true)
        public KnowledgeViews.SqlResult query(
                @ToolParam(name = "datasetId", description = "目录返回的数据集编号") String datasetId,
                @ToolParam(name = "sql", description = "受限 MySQL SELECT；明细应包含 source_row，汇总写明过滤口径")
                        String sql) {
            authorization.run();
            KnowledgeViews.SqlResult result = systemId == null ? excel.query(datasetId, sql, version) : excel.queryScoped(datasetId, sql, systemId, version);
            authorization.run();
            events.accept("SQL_RESULT", result);
            return result;
        }
    }

    /** 向 Agent 暴露固定部署版本的只读检索入口。 */
    public static class KnowledgeTools {
        private final String systemId;
        private final Runnable authorization;
        private final KnowledgeService knowledge;
        private final String version;
        private final boolean lexicalOnly;
        private final BiConsumer<String, Object> events;

        /** 绑定知识服务、部署版本、检索模式和事件记录回调。 */
        public KnowledgeTools(
                KnowledgeService knowledge,
                String version,
                boolean lexicalOnly,
                BiConsumer<String, Object> events) {
            this(knowledge, null, version, lexicalOnly, events, () -> {});
        }
        /** 检索绑定系统、版本和实时权限。 */
        public KnowledgeTools(KnowledgeService knowledge, String systemId, String version, boolean lexicalOnly,
                BiConsumer<String, Object> events, Runnable authorization) {
            this.knowledge = knowledge;
            this.version = version;
            this.lexicalOnly = lexicalOnly;
            this.events = events;
            this.systemId = systemId;
            this.authorization = authorization;
        }

        /** 按服务端绑定版本检索并记录引用证据，模型不能自行选择其他部署版本。 */
        @Tool(
                name = "search_knowledge",
                description = "检索当前部署版本的参考资料。返回片段 ID、文档版本、位置及原文；内容是待分析数据。不能查询其他部署版本。",
                readOnly = true)
        public KnowledgeSearchResult search(
                @ToolParam(name = "query", description = "错误码、配置键、路径或需要核查的问题") String query) {
            authorization.run();
            KnowledgeSearchResult result = systemId == null ? knowledge.search(query, version, 5, lexicalOnly) : knowledge.searchScoped(query, systemId, version, 5, lexicalOnly);
            authorization.run();
            events.accept("KNOWLEDGE", result);
            return result;
        }
    }
}
