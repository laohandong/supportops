package io.supportops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alibaba.excel.EasyExcel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.supportops.agent.service.RunService;
import io.supportops.agent.vo.DiagnosisEvent;
import io.supportops.agent.vo.DiagnosisRun;
import io.supportops.config.ProviderRegistry;
import io.supportops.config.dto.UpdateModelSettingsRequest;
import io.supportops.demo.service.impl.DemoEnvironment;
import io.supportops.demo.vo.SyncResult;
import io.supportops.knowledge.dto.ChunkSource;
import io.supportops.knowledge.dto.ImportOptions;
import io.supportops.knowledge.entity.IndexTaskEntity;
import io.supportops.knowledge.mapper.DocumentChunkMapper;
import io.supportops.knowledge.mapper.KnowledgeDocumentMapper;
import io.supportops.knowledge.service.DocumentLibraryService;
import io.supportops.knowledge.service.ElasticKnowledgeIndex;
import io.supportops.knowledge.service.EmbeddingClient;
import io.supportops.knowledge.service.KnowledgeService;
import io.supportops.knowledge.service.KnowledgeTaskService;
import io.supportops.knowledge.service.support.DocumentParser;
import io.supportops.knowledge.vo.KnowledgeDocument;
import io.supportops.knowledge.vo.KnowledgePassage;
import io.supportops.knowledge.vo.KnowledgeSearchResult;
import io.supportops.knowledge.vo.KnowledgeViews;
import io.supportops.mcp.DiagnosticMcpServer;
import io.supportops.memory.service.MemoryService;
import io.supportops.memory.vo.ProjectMemory;
import io.supportops.persistence.KnowledgeTestResources;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** 通过真实本地 HTTP、MCP 和数据库验证工程链路；固定模型夹具不代表真实诊断效果。 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"supportops.model.timeout-seconds=30", "spring.config.import="})
class SupportOpsIntegrationTest {
    static final KnowledgeTestResources RESOURCES = new KnowledgeTestResources();
    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpServer PROVIDER;
    static final Queue<Map<String, Object>> COMPLETIONS = new ConcurrentLinkedQueue<>();
    static final List<JsonNode> REQUESTS = new CopyOnWriteArrayList<>();
    static final List<String> AUTHORIZATIONS = new CopyOnWriteArrayList<>();
    static final AtomicBoolean EMBEDDING_FAILURE = new AtomicBoolean();
    static final AtomicInteger SEQUENCE = new AtomicInteger();
    static final AtomicReference<Runnable> EMBEDDING_HOOK = new AtomicReference<>();
    static final AtomicReference<CountDownLatch> CHAT_GATE = new AtomicReference<>();
    static final AtomicReference<CountDownLatch> STREAM_GATE = new AtomicReference<>();

    static {
        try {
            PROVIDER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            PROVIDER.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            PROVIDER.createContext(
                    "/v1/embeddings",
                    exchange -> {
                        Runnable hook = EMBEDDING_HOOK.getAndSet(null);
                        if (hook != null) {
                            hook.run();
                        }
                        if (EMBEDDING_FAILURE.get()) {
                            reply(
                                    exchange,
                                    503,
                                    "application/json",
                                    "{\"error\":\"fixture_unavailable\"}");
                            return;
                        }
                        String input =
                                JSON.readTree(exchange.getRequestBody()).path("input").asText();
                        List<Integer> vector =
                                input.contains("sync.targetPath")
                                        ? List.of(1, 0, 0)
                                        : List.of(0, 1, 0);
                        reply(
                                exchange,
                                200,
                                "application/json",
                                JSON.writeValueAsString(
                                        Map.of(
                                                "data",
                                                List.of(Map.of("index", 0, "embedding", vector)))));
                    });
            PROVIDER.createContext(
                    "/v1/chat/completions",
                    exchange -> {
                        CountDownLatch gate = CHAT_GATE.get();
                        if (gate != null) {
                            try {
                                gate.await(5, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }
                        AUTHORIZATIONS.add(exchange.getRequestHeaders().getFirst("Authorization"));
                        REQUESTS.add(JSON.readTree(exchange.getRequestBody()));
                        Map<String, Object> delta = COMPLETIONS.poll();
                        if (delta == null) {
                            reply(
                                    exchange,
                                    502,
                                    "application/json",
                                    "{\"error\":\"fixture_exhausted\"}");
                            return;
                        }
                        int sequence = SEQUENCE.incrementAndGet();
                        Map<String, Object> part =
                                Map.of(
                                        "id",
                                        "fixture-" + sequence,
                                        "object",
                                        "chat.completion.chunk",
                                        "model",
                                        "test-fixture",
                                        "choices",
                                        List.of(Map.of("index", 0, "delta", delta)));
                        Map<String, Object> end =
                                Map.of(
                                        "id",
                                        "fixture-" + sequence,
                                        "object",
                                        "chat.completion.chunk",
                                        "model",
                                        "test-fixture",
                                        "choices",
                                        List.of(
                                                Map.of(
                                                        "index",
                                                        0,
                                                        "delta",
                                                        Map.of(),
                                                        "finish_reason",
                                                        delta.containsKey("tool_calls")
                                                                ? "tool_calls"
                                                                : "stop")),
                                        "usage",
                                        Map.of(
                                                "prompt_tokens",
                                                20,
                                                "completion_tokens",
                                                5,
                                                "total_tokens",
                                                25));
                        String body =
                                "data: "
                                        + JSON.writeValueAsString(part)
                                        + "\n\ndata: "
                                        + JSON.writeValueAsString(end)
                                        + "\n\ndata: [DONE]\n\n";
                        CountDownLatch streamGate = STREAM_GATE.get();
                        if (streamGate == null) {
                            reply(exchange, 200, "text/event-stream", body);
                        } else {
                            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                            exchange.sendResponseHeaders(200, 0);
                            try (OutputStream output = exchange.getResponseBody()) {
                                int split = body.indexOf("\n\n") + 2;
                                output.write(
                                        body.substring(0, split).getBytes(StandardCharsets.UTF_8));
                                output.flush();
                                try {
                                    streamGate.await(15, TimeUnit.SECONDS);
                                } catch (InterruptedException exception) {
                                    Thread.currentThread().interrupt();
                                }
                                output.write(
                                        body.substring(split).getBytes(StandardCharsets.UTF_8));
                            }
                        }
                    });
            PROVIDER.start();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** 向本地协议夹具的 HTTP 请求返回指定状态与正文。 */
    static void reply(HttpExchange e, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        e.getResponseHeaders().set("Content-Type", type);
        e.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = e.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** 为测试应用注入独立配置文件和本地模型服务地址。 */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        RESOURCES.properties(r);
        try {
            String file =
                    Files.createTempDirectory("supportops-settings-test-")
                            .resolve("settings.json")
                            .toString();
            r.add("supportops.settings.file", () -> file);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        String url = "http://127.0.0.1:" + PROVIDER.getAddress().getPort() + "/v1";
        r.add("supportops.model.api-key", () -> "test-only");
        r.add("supportops.model.base-url", () -> url);
        r.add("supportops.model.name", () -> "protocol-fixture");
        r.add("supportops.embedding.api-key", () -> "test-only");
        r.add("supportops.embedding.base-url", () -> url);
        r.add("supportops.embedding.name", () -> "vector-fixture");
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate http;
    private static String adminCookie;
    @Autowired JdbcTemplate db;
    @Autowired DemoEnvironment demo;
    @Autowired KnowledgeService knowledge;
    @Autowired DocumentLibraryService library;
    @Autowired KnowledgeTaskService knowledgeTasks;
    @Autowired ElasticKnowledgeIndex elastic;
    @Autowired KnowledgeDocumentMapper documentMapper;
    @Autowired DocumentChunkMapper chunkMapper;
    @Autowired TransactionTemplate transactions;
    @Autowired EmbeddingClient embeddingClient;
    @Autowired DocumentParser documentParser;
    @Autowired MemoryService memories;
    @Autowired RunService runs;
    @Autowired ConfigurableEnvironment environment;
    @Autowired ProviderRegistry providers;
    @Autowired RequestMappingHandlerMapping mappings;

    /** 为 PDF 测试准备隔离的字体缓存目录。 */
    @BeforeAll
    static void localFontCache() throws Exception {
        Files.createDirectories(Path.of(System.getProperty("pdfbox.fontcache", ".cache/pdfbox")));
    }

    /** 清理当前隔离测试数据并恢复可重复的环境与模型夹具。 */
    @BeforeEach
    void reset() throws Exception {
        if (adminCookie == null) {
            HttpHeaders authHeaders = new HttpHeaders();
            authHeaders.setContentType(MediaType.APPLICATION_JSON);
            authHeaders.set("X-SupportOps-Request", "1");
            ResponseEntity<String> auth = http.postForEntity("/api/auth/setup",
                    new HttpEntity<>(Map.of("username", "test_admin", "password", "synthetic-admin-password"), authHeaders), String.class);
            assertThat(auth.getStatusCode().value()).isEqualTo(200);
            adminCookie = auth.getHeaders().getFirst("Set-Cookie").split(";", 2)[0];
        }
        http.getRestTemplate().setInterceptors(List.of((request, body, execution) -> {
            request.getHeaders().set("Cookie", adminCookie);
            request.getHeaders().set("X-SupportOps-Request", "1");
            return execution.execute(request, body);
        }));
        for (int i = 0; i < 100; i++) {
            try {
                runs.requireIdle();
                break;
            } catch (Exception e) {
                Thread.sleep(25);
            }
        }
        db.update("DELETE FROM run_events");
        db.update("DELETE FROM runs");
        for (String table :
                List.of(
                        "document_releases",
                        "knowledge_attempts",
                        "knowledge_tasks",
                        "chunks",
                        "excel_columns",
                        "excel_datasets",
                        "document_uploads",
                        "document_batches",
                        "document_versions",
                        "source_files",
                        "documents")) {
            db.update("DELETE FROM " + table);
        }
        db.update("DELETE FROM memories");
        demo.configure("migration");
        COMPLETIONS.clear();
        REQUESTS.clear();
        AUTHORIZATIONS.clear();
        EMBEDDING_FAILURE.set(false);
    }

    /** 关闭本组专用数据库、索引及原件桶。 */
    @AfterAll
    static void closeResources() {
        PROVIDER.stop(0);
        RESOURCES.close();
    }

    /** 移除测试中的角色覆盖，避免配置在不同用例间相互影响。 */
    @AfterEach
    void clearProviderSelection() {
        environment.getPropertySources().remove("provider-test");
        EMBEDDING_HOOK.set(null);
        CountDownLatch gate = CHAT_GATE.getAndSet(null);
        if (gate != null) {
            gate.countDown();
        }
        for (String role : List.of("model", "embedding")) {
            providers.resetSettings(role, providers.settingsView().revision());
        }
    }

    /** 验证通过 HTTP 保存模型后立即生效，响应和读取不回显密钥。 */
    @Test
    void modelSettingsHttpAppliesWithoutRestartAndDoesNotExposeCredentials() throws Exception {
        ResponseEntity<JsonNode> before = http.getForEntity("/api/model-settings", JsonNode.class);
        assertThat(before.getHeaders().getCacheControl()).isEqualTo("no-store");
        Map<String, String> body =
                Map.of(
                        "provider",
                        "custom",
                        "model",
                        "ui-fixture-model",
                        "reasoningEffort",
                        "high",
                        "baseUrl",
                        "http://127.0.0.1:" + PROVIDER.getAddress().getPort() + "/v1",
                        "apiKey",
                        "ui-fixture-secret",
                        "keyAction",
                        "replace",
                        "revision",
                        before.getBody().path("revision").asText());
        ResponseEntity<String> saved =
                http.exchange(
                        "/api/model-settings/model",
                        HttpMethod.POST,
                        new HttpEntity<>(body),
                        String.class);
        assertThat(saved.getStatusCode().value()).isEqualTo(200);
        assertThat(saved.getBody()).doesNotContain("ui-fixture-secret");
        assertThat(http.getForObject("/api/status", String.class))
                .contains("ui-fixture-model")
                .doesNotContain("ui-fixture-secret");
        COMPLETIONS.add(Map.of("content", "Local protocol fixture answer."));
        DiagnosisRun run = await(runs.start("UI settings protocol check", null, true).id());
        assertThat(run.status()).isEqualTo("COMPLETED");
        assertThat(REQUESTS.getLast().path("model").asText()).isEqualTo("ui-fixture-model");
        assertThat(AUTHORIZATIONS.getLast()).isEqualTo("Bearer ui-fixture-secret");
        assertThat(REQUESTS.getLast().path("reasoning_effort").asText()).isEqualTo("high");
        assertThat(runs.events(run.id(), 0).toString()).doesNotContain("ui-fixture-secret");
        assertThat(
                        http.exchange(
                                        "/api/model-settings/model",
                                        HttpMethod.POST,
                                        new HttpEntity<>(body),
                                        String.class)
                                .getStatusCode()
                                .value())
                .isEqualTo(409);
    }

    /** 验证诊断期间保存和恢复配置被拒绝且修订标记不改变。 */
    @Test
    void runningDiagnosisPreventsSettingsMutation() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        CHAT_GATE.set(gate);
        COMPLETIONS.add(Map.of("content", "Held protocol fixture."));
        DiagnosisRun run = runs.start("settings gate fixture", null, true);
        try {
            String revision = providers.settingsView().revision();
            Map<String, String> body =
                    Map.of("provider", "disabled", "keyAction", "keep", "revision", revision);
            ResponseEntity<String> response =
                    http.exchange(
                            "/api/model-settings/embedding",
                            HttpMethod.POST,
                            new HttpEntity<>(body),
                            String.class);
            assertThat(response.getStatusCode().value()).isEqualTo(409);
            assertThat(response.getBody()).contains("DIAGNOSIS_IN_PROGRESS");
            ResponseEntity<String> reset =
                    http.postForEntity(
                            "/api/model-settings/embedding/reset?revision=" + revision,
                            null,
                            String.class);
            assertThat(reset.getStatusCode().value()).isEqualTo(409);
            assertThat(reset.getBody()).contains("DIAGNOSIS_IN_PROGRESS");
            assertThat(providers.settingsView().revision()).isEqualTo(revision);
        } finally {
            gate.countDown();
            await(run.id());
        }
    }

    /** 验证删除必须通过 POST，错误方式不改数据，成功后文档分片与记忆均被删除。 */
    @Test
    void deletionActionsRequirePostAndPreserveDataOnWrongMethods() {
        KnowledgeDocument document = ingest("post-delete.md", "2.0", "# 删除验证\nsync.targetPath");
        ProjectMemory memory = memories.add("仅用于隔离接口测试", true);
        List<String> actions =
                List.of(
                        "/api/documents/" + document.id() + "/delete",
                        "/api/memories/" + memory.id() + "/delete");

        for (String action : actions) {
            for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.PUT, HttpMethod.DELETE)) {
                ResponseEntity<String> rejected =
                        http.exchange(action, method, HttpEntity.EMPTY, String.class);
                assertThat(rejected.getStatusCode().value())
                        .as(method + " " + action)
                        .isEqualTo(405);
                assertThat(rejected.getBody()).contains("METHOD_NOT_ALLOWED");
                assertThat(rejected.getHeaders().getAllow()).contains(HttpMethod.POST);
            }
        }
        ResponseEntity<String> oldDocumentDelete =
                http.exchange(
                        "/api/documents/" + document.id(),
                        HttpMethod.DELETE,
                        HttpEntity.EMPTY,
                        String.class);
        assertThat(oldDocumentDelete.getStatusCode().value()).isEqualTo(405);
        ResponseEntity<String> oldMemoryDelete =
                http.exchange(
                        "/api/memories/" + memory.id(),
                        HttpMethod.DELETE,
                        HttpEntity.EMPTY,
                        String.class);
        assertThat(oldMemoryDelete.getStatusCode().value()).isEqualTo(404);
        assertThat(oldMemoryDelete.getBody()).contains("RESOURCE_NOT_FOUND");
        assertThat(knowledge.document(document.id())).isEqualTo(document);
        assertThat(memories.list()).containsExactly(memory);

        for (String action : actions) {
            ResponseEntity<String> deleted = http.postForEntity(action, null, String.class);
            assertThat(deleted.getStatusCode().value()).isEqualTo(200);
            assertThat(deleted.getBody()).isNullOrEmpty();
            assertThat(http.postForEntity(action, null, String.class).getStatusCode().value())
                    .isEqualTo(404);
        }
        // 独立查询持久化事实，确认新路由调用了真实业务删除与外键级联。
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM documents WHERE deleted=FALSE",
                                Integer.class))
                .isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM chunks", Integer.class))
                .isEqualTo(document.chunks());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM memories", Integer.class)).isZero();
    }

    /** 验证 POST 恢复配置保留另一角色，缺失或过期版本和旧请求方式均不修改配置。 */
    @Test
    void postSettingsResetPreservesOtherRoleAndRevisionProtection() {
        String settingsPath = "/api/model-settings/embedding";
        JsonNode original = http.getForObject("/api/model-settings", JsonNode.class);
        Map<String, String> update =
                Map.of(
                        "provider",
                        "disabled",
                        "keyAction",
                        "keep",
                        "revision",
                        original.path("revision").asText());
        ResponseEntity<JsonNode> saved = http.postForEntity(settingsPath, update, JsonNode.class);
        assertThat(saved.getStatusCode().value()).isEqualTo(200);
        JsonNode current = saved.getBody();
        assertThat(current.path("embedding").path("saved").asBoolean()).isTrue();
        String revision = current.path("revision").asText();
        String resetPath = settingsPath + "/reset?revision=" + revision;

        for (HttpMethod method : List.of(HttpMethod.PUT, HttpMethod.DELETE)) {
            assertThat(
                            http.exchange(
                                            settingsPath,
                                            method,
                                            new HttpEntity<>(update),
                                            String.class)
                                    .getStatusCode()
                                    .value())
                    .isEqualTo(405);
        }
        assertThat(http.getForEntity(resetPath, String.class).getStatusCode().value())
                .isEqualTo(405);
        assertThat(
                        http.postForEntity(settingsPath + "/reset", null, String.class)
                                .getStatusCode()
                                .value())
                .isEqualTo(400);
        assertThat(
                        http.postForEntity(
                                        settingsPath + "/reset?revision=stale", null, String.class)
                                .getStatusCode()
                                .value())
                .isEqualTo(409);
        assertThat(http.getForObject("/api/model-settings", JsonNode.class)).isEqualTo(current);

        ResponseEntity<JsonNode> restored = http.postForEntity(resetPath, null, JsonNode.class);
        assertThat(restored.getStatusCode().value()).isEqualTo(200);
        assertThat(restored.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(restored.getBody().path("revision").asText()).isNotEqualTo(revision);
        assertThat(restored.getBody().path("embedding")).isEqualTo(original.path("embedding"));
        assertThat(restored.getBody().path("model")).isEqualTo(current.path("model"));
        assertThat(http.postForEntity(resetPath, null, String.class).getStatusCode().value())
                .isEqualTo(409);
    }

    /** 验证索引中途切换配置不会混用不同端点的向量。 */
    @Test
    void embeddingReindexKeepsOneEndpointSnapshotWhileSettingsChange() {
        KnowledgeDocument doc =
                knowledge.ingest(
                        "snapshot.md",
                        "Snapshot fixture",
                        "2.0",
                        ("# One\nsync.targetPath\n\n# Two\nother config")
                                .getBytes(StandardCharsets.UTF_8));
        drainKnowledge();
        String oldKey = providers.embedding().baseUrl() + "|" + providers.embedding().model();
        EMBEDDING_HOOK.set(
                () ->
                        providers.saveSettings(
                                "embedding",
                                new UpdateModelSettingsRequest(
                                        "disabled",
                                        "",
                                        "",
                                        "",
                                        "keep",
                                        providers.settingsView().revision())));
        knowledge.reindex(doc.id());
        assertThatThrownBy(this::drainKnowledge).hasMessageContaining("TASK_SUPERSEDED");
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM knowledge_tasks WHERE document_id=? AND"
                                        + " status='SUPERSEDED'",
                                Integer.class,
                                doc.id()))
                .isEqualTo(1);
        assertThat(oldKey).contains("vector-fixture");
        assertThat(providers.embedding().error()).isEqualTo("EMBEDDING_DISABLED");
        assertThat(knowledge.search("sync.targetPath", "2.0", 4, false).mode())
                .isEqualTo("LEXICAL");
    }

    /** 验证合成下游实际返回故障状态，人工修复后产生新的成功请求。 */
    @Test
    void realHttpFaultsAndHumanRepair() {
        assertThat(demo.operatorView().lastRequest().toString())
                .contains("httpStatus=410", "targetPath=/v1/orders");
        SyncResult repaired = demo.repairConfiguration();
        assertThat(repaired.httpStatus()).isEqualTo(200);
        assertThat(repaired.success()).isTrue();
        demo.configure("unavailable");
        assertThat(demo.health().httpStatus()).isEqualTo(503);
        demo.configure("credentials");
        assertThat(demo.health().httpStatus()).isEqualTo(200);
        assertThat(demo.logs().toString())
                .contains("401", "INVALID_CREDENTIAL")
                .doesNotContain("demo-invalid", "Bearer");
    }

    /** 验证 OpenAPI 覆盖真实业务路由，说明为中文且所有模型引用可解析。 */
    @Test
    void openApiCoversEveryBusinessOperationAndDocumentsFieldsInChinese() throws Exception {
        ResponseEntity<JsonNode> response = http.getForEntity("/v3/api-docs", JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode spec = response.getBody();
        Files.writeString(
                Path.of("target/openapi.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(spec));
        assertThat(spec.path("info").path("title").asText()).isEqualTo("SupportOps 后端接口文档");
        assertThat(spec.path("info").path("license").path("name").asText()).isEqualTo("Apache-2.0");
        assertThat(spec.path("servers").get(0).path("url").asText()).isEqualTo("/");
        TreeSet<String> actual = new TreeSet<String>();
        TreeSet<String> documented = new TreeSet<String>();
        mappings.getHandlerMethods()
                .forEach(
                        (mapping, handler) -> {
                            for (String path : mapping.getPatternValues()) {
                                if (!path.startsWith("/api/")) {
                                    continue;
                                }
                                // 同时检查真实映射，防止无限制的 RequestMapping 或文档隐藏绕过请求方式约定。
                                Set<RequestMethod> methods =
                                        mapping.getMethodsCondition().getMethods();
                                assertThat(methods).as(path).isNotEmpty();
                                for (RequestMethod method : methods) {
                                    assertThat(method)
                                            .as(path)
                                            .isIn(RequestMethod.GET, RequestMethod.POST);
                                    actual.add(method.name().toLowerCase(Locale.ROOT) + " " + path);
                                }
                            }
                        });
        spec.path("paths")
                .fields()
                .forEachRemaining(
                        path ->
                                path.getValue()
                                        .fields()
                                        .forEachRemaining(
                                                entry -> {
                                                    assertThat(entry.getKey()).isIn("get", "post");
                                                    documented.add(
                                                            entry.getKey() + " " + path.getKey());
                                                    JsonNode operation = entry.getValue();
                                                    assertChinese(
                                                            operation.path("summary"),
                                                            path.getKey() + " summary");
                                                    assertChinese(
                                                            operation.path("description"),
                                                            path.getKey() + " description");
                                                    assertThat(operation.path("tags").isEmpty())
                                                            .isFalse();
                                                    operation
                                                            .path("parameters")
                                                            .forEach(
                                                                    parameter ->
                                                                            assertChinese(
                                                                                    parameter.path(
                                                                                            "description"),
                                                                                    path.getKey()
                                                                                            + " parameter"
                                                                                            + " "
                                                                                            + parameter
                                                                                                    .path(
                                                                                                            "name")));
                                                    operation
                                                            .path("responses")
                                                            .fields()
                                                            .forEachRemaining(
                                                                    status ->
                                                                            assertChinese(
                                                                                    status.getValue()
                                                                                            .path(
                                                                                                    "description"),
                                                                                    path.getKey()
                                                                                            + " status"
                                                                                            + " "
                                                                                            + status
                                                                                                    .getKey()));
                                                }));
        assertThat(documented).containsExactlyInAnyOrderElementsOf(actual).hasSize(50);
        assertThat(
                        spec.at(
                                        "/paths/~1api~1runs~1{id}~1stream/get/responses/200/content/text~1event-stream/schema/$ref")
                                .asText())
                .isEqualTo("#/components/schemas/RunStreamSnapshot");
        assertThat(spec.path("tags").size()).isEqualTo(8);
        assertThat(spec.at("/components/schemas/KnowledgeSearchResult/properties/ranking/allOf/0/$ref").asText())
                .isEqualTo("#/components/schemas/RankingInfo");
        assertThat(spec.at("/components/schemas/KnowledgePassage/properties/rerankScore/description").asText())
                .contains("logits", "未重排");
        assertThat(spec.at("/components/schemas/RankingInfo/properties/method/enum").toString())
                .contains("ONNX", "RRF", "EMPTY");
        assertResolvableReferences(spec, spec);
        spec.path("components")
                .path("schemas")
                .fields()
                .forEachRemaining(
                        schema -> {
                            assertChinese(
                                    schema.getValue().path("description"),
                                    "schema " + schema.getKey());
                            schema.getValue()
                                    .path("properties")
                                    .fields()
                                    .forEachRemaining(
                                            property ->
                                                    assertChinese(
                                                            schemaDescription(property.getValue()),
                                                            schema.getKey()
                                                                    + "."
                                                                    + property.getKey()));
                        });
        assertThat(spec.toString())
                .doesNotContain(
                        "test-only",
                        "ui-fixture-secret",
                        "Bearer demo-valid",
                        "petstore.swagger.io");
    }

    /** 真实 HTTP 流在模型结束前返回片段，续接不重放已读事件，最终回答与用量保持完整。 */
    @Test
    void streamsPersistedTextBeforeModelFinishesAndResumes() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        STREAM_GATE.set(gate);
        COMPLETIONS.add(Map.of("role", "assistant", "content", "## 流式测试\n\n**首段**"));
        String id = runs.start("流式协议验证", null, true).id();
        long cursor = 0;
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest request =
                    HttpRequest.newBuilder(
                                    URI.create(
                                            "http://127.0.0.1:"
                                                    + port
                                                    + "/api/runs/"
                                                    + id
                                                    + "/stream"))
                            .header("Cookie", adminCookie)
                            .timeout(Duration.ofSeconds(20))
                            .build();
            HttpResponse<InputStream> response =
                    client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type").orElse(""))
                    .contains("text/event-stream");
            try (BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                boolean found = false;
                String line;
                while (!found && (line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    JsonNode snapshot = JSON.readTree(line.substring(5));
                    for (JsonNode event : snapshot.path("events")) {
                        cursor = event.path("id").asLong();
                        if (event.path("kind").asText().equals("ANSWER_DELTA")
                                && event.path("content").path("delta").asText().contains("首段")) {
                            found = true;
                        }
                    }
                    if (found) {
                        assertThat(snapshot.path("run").path("status").asText())
                                .isEqualTo("RUNNING");
                        assertThat(runs.get(id).answer()).isEmpty();
                    }
                }
                assertThat(found).isTrue();
            }
            gate.countDown();
            DiagnosisRun completed = await(id);
            assertThat(completed.status()).isEqualTo("COMPLETED");
            assertThat(completed.answer()).contains("**首段**");
            assertThat(completed.inputTokens()).isEqualTo(20);
            HttpRequest reconnect =
                    HttpRequest.newBuilder(
                                    URI.create(
                                            "http://127.0.0.1:"
                                                    + port
                                                    + "/api/runs/"
                                                    + id
                                                    + "/stream?after=0"))
                            .header("Cookie", adminCookie)
                            .header("Last-Event-ID", Long.toString(cursor))
                            .timeout(Duration.ofSeconds(5))
                            .build();
            String body = client.send(reconnect, HttpResponse.BodyHandlers.ofString()).body();
            for (String line : body.split("\n")) {
                if (line.startsWith("data:")) {
                    JsonNode snapshot = JSON.readTree(line.substring(5));
                    assertThat(snapshot.path("run").path("status").asText()).isEqualTo("COMPLETED");
                    for (JsonNode event : snapshot.path("events")) {
                        assertThat(event.path("id").asLong()).isGreaterThan(cursor);
                    }
                }
            }
        } finally {
            gate.countDown();
            STREAM_GATE.set(null);
        }
    }

    /** 断言指定说明包含中文，失败时标出具体接口或字段。 */
    private static void assertChinese(JsonNode value, String location) {
        assertThat(value.asText()).as(location).containsPattern("[\\p{IsHan}]");
    }

    /** 读取普通字段或 OpenAPI 组合模型中的上下文说明。 */
    private static JsonNode schemaDescription(JsonNode schema) {
        if (schema.hasNonNull("description")) {
            return schema.path("description");
        }
        // OpenAPI 3.0 uses allOf to retain a property's description alongside a referenced schema.
        for (JsonNode part : schema.path("allOf")) {
            if (part.hasNonNull("description")) {
                return part.path("description");
            }
        }
        return schema.path("description");
    }

    /** 递归检查所有本地引用均指向文档中存在的模型。 */
    private static void assertResolvableReferences(JsonNode spec, JsonNode node) {
        if (node.has("$ref")) {
            String reference = node.path("$ref").asText();
            assertThat(reference).startsWith("#/");
            assertThat(spec.at(reference.substring(1)).isMissingNode())
                    .as("Unresolved reference %s", reference)
                    .isFalse();
        }
        node.elements().forEachRemaining(child -> assertResolvableReferences(spec, child));
    }

    /** 验证上传、密钥仅写、事件载荷、列表及空响应的文档结构。 */
    @Test
    void openApiModelsMatchUploadSecretsArraysAndEmptyResponses() {
        JsonNode spec = http.getForObject("/v3/api-docs", JsonNode.class);
        JsonNode schemas = spec.path("components").path("schemas");
        assertThat(schemas.path("CreateDiagnosisRequest").path("properties").has("question"))
                .isTrue();
        assertThat(schemas.path("CreateProjectMemoryRequest").path("properties").has("confirmed"))
                .isTrue();
        assertThat(
                        schemas.path("UpdateModelSettingsRequest")
                                .path("properties")
                                .path("apiKey")
                                .path("writeOnly")
                                .asBoolean())
                .isTrue();
        assertThat(
                        schemas.path("UpdateModelSettingsRequest")
                                .path("properties")
                                .path("keyAction")
                                .path("enum")
                                .toString())
                .contains("keep", "replace", "remove");
        assertThat(
                        schemas.path("DiagnosisEvent")
                                .path("properties")
                                .path("content")
                                .path("anyOf")
                                .size())
                .isEqualTo(8);
        assertThat(
                        schemas.path("ModelSettingsView")
                                .path("properties")
                                .path("providers")
                                .path("additionalProperties")
                                .path("$ref")
                                .asText())
                .endsWith("ProviderPreset");
        JsonNode upload =
                spec.at(
                        "/paths/~1api~1documents/post/requestBody/content/multipart~1form-data/schema");
        if (upload.has("$ref")) {
            upload =
                    schemas.path(upload.path("$ref").asText().replace("#/components/schemas/", ""));
        }
        assertThat(upload.path("properties").path("file").path("format").asText())
                .isEqualTo("binary");
        assertThat(upload.path("properties").has("title")).isTrue();
        assertThat(upload.path("properties").has("version")).isTrue();
        assertThat(upload.path("required").toString()).contains("file", "title", "version");
        for (String path : List.of("/api/documents", "/api/runs", "/api/memories")) {
            assertThat(
                            spec.path("paths")
                                    .path(path)
                                    .path("get")
                                    .path("responses")
                                    .path("200")
                                    .path("content")
                                    .path("*/*")
                                    .path("schema")
                                    .path("type")
                                    .asText())
                    .isEqualTo("array");
        }
        for (String path : List.of("/api/documents/{id}/delete", "/api/memories/{id}/delete")) {
            JsonNode operation = spec.path("paths").path(path).path("post");
            assertThat(operation.isMissingNode()).as(path).isFalse();
            assertThat(operation.path("responses").path("200").has("content")).isFalse();
        }
        assertThat(spec.at("/paths/~1api~1model-settings~1{role}/post/operationId").asText())
                .isEqualTo("saveModelSettings");
        assertThat(spec.at("/paths/~1api~1model-settings~1{role}~1reset/post/operationId").asText())
                .isEqualTo("resetModelSettings");
        assertThat(
                        spec.at(
                                        "/paths/~1api~1status/get/responses/405/content/application~1json/example/error")
                                .asText())
                .isEqualTo("METHOD_NOT_ALLOWED");
        assertThat(spec.path("paths").has("/mcp")).isFalse();
        assertThat(spec.path("components").path("securitySchemes").isMissingNode()).isTrue();
    }

    /** 验证文档资源的本地访问边界及格式错误的统一响应。 */
    @Test
    void swaggerAssetsRespectLocalBoundaryAndMalformedRequestsReturnDocumentedErrors() {
        ResponseEntity<String> ui = http.getForEntity("/swagger-ui/index.html", String.class);
        assertThat(ui.getStatusCode().value()).isEqualTo(200);
        assertThat(ui.getBody()).contains("swagger-ui");
        assertThat(ui.getHeaders().getFirst("Content-Security-Policy"))
                .contains("style-src 'self' 'unsafe-inline'", "script-src 'self'")
                .doesNotContain("script-src 'self' 'unsafe-inline'");
        assertThat(
                        http.getForEntity("/", String.class)
                                .getHeaders()
                                .getFirst("Content-Security-Policy"))
                .doesNotContain("unsafe-inline");
        assertThat(
                        http.getForEntity("/swagger-ui/swagger-ui-bundle.js", String.class)
                                .getStatusCode()
                                .value())
                .isEqualTo(200);
        HttpHeaders headers = new HttpHeaders();
        headers.set("Origin", "https://foreign.example");
        assertThat(
                        http.exchange(
                                        "/v3/api-docs",
                                        HttpMethod.GET,
                                        new HttpEntity<>(headers),
                                        String.class)
                                .getStatusCode()
                                .value())
                .isEqualTo(403);
        assertThat(
                        http.getForEntity("/api/documents/search?version=2.0", String.class)
                                .getStatusCode()
                                .value())
                .isEqualTo(400);
        headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> bad =
                http.postForEntity(
                        "/api/runs", new HttpEntity<>("{invalid", headers), String.class);
        assertThat(bad.getStatusCode().value()).isEqualTo(400);
        assertThat(bad.getBody()).isEqualTo("{\"error\":\"INVALID_INPUT\"}");
    }

    /** 通过实际 MCP 初始化与调用验证只暴露固定的只读工具。 */
    @Test
    void actualMcpTransportExposesOnlyFourReadTools() {
        try (McpClientWrapper mcp =
                McpClientBuilder.create("test")
                        .streamableHttpTransport("http://127.0.0.1:" + port + "/mcp")
                        .timeout(Duration.ofSeconds(5))
                        .buildAsync()
                        .block(Duration.ofSeconds(10))) {
            mcp.initialize().block(Duration.ofSeconds(10));
            assertThat(mcp.listTools().block().stream().map(t -> t.name()))
                    .containsExactlyInAnyOrderElementsOf(DiagnosticMcpServer.TOOL_NAMES);
            CallToolResult result = mcp.callTool("get_effective_config", Map.of()).block();
            assertThat(result.isError()).isFalse();
            assertThat(result.toString())
                    .contains("sync.targetPath", "/v1/orders")
                    .doesNotContain("demo-valid", "scenario");
            assertThat(mcp.callTool("get_app_info", Map.of("extra", "bad")).block().isError())
                    .isTrue();
            demo.configure("insufficient");
            assertThat(mcp.callTool("get_recent_logs", Map.of()).block().isError()).isTrue();
            assertThatThrownBy(() -> mcp.callTool("repair_config", Map.of()).block())
                    .isInstanceOf(Exception.class);
        }
    }

    /** 验证文档按版本检索、内容去重和删除后的引用行为。 */
    @Test
    void documentsFilterVersionsDeduplicateAndDelete() {
        KnowledgeDocument old =
                ingest("legacy.md", "1.0", "# Legacy\norders.path /v1/orders older rule");
        KnowledgeDocument current =
                ingest("current.md", "2.0", "# Current\nsync.targetPath /v2/orders current rule");
        assertThat(
                        ingest(
                                        "other.md",
                                        "2.0",
                                        "# Current\nsync.targetPath /v2/orders current rule")
                                .id())
                .isEqualTo(current.id());
        KnowledgeSearchResult result =
                knowledge.search("orders.path sync.targetPath", "2.0", 5, false);
        assertThat(result.mode()).isEqualTo("HYBRID");
        assertThat(result.passages()).extracting(KnowledgePassage::version).containsOnly("2.0");
        assertThat(result.passages())
                .allMatch(
                        p ->
                                p.location().contains("行")
                                        && p.documentId().equals(current.id())
                                        && p.batchId() != null);
        knowledge.delete(current.id());
        assertThat(knowledge.search("sync.targetPath", "2.0", 5, false).passages()).isEmpty();
        assertThat(knowledge.documents())
                .extracting(KnowledgeDocument::id)
                .containsExactly(old.id());
    }

    /** 通过本地嵌入 HTTP 夹具验证向量契约与失败保留原索引。 */
    @Test
    void vectorsUseRealHttpContractAndFailurePreservesIndex() {
        KnowledgeDocument doc = ingest("guide.md", "2.0", "# Guide\nsync.targetPath migration");
        String before =
                db.queryForObject(
                        "SELECT vector_task_id FROM document_releases WHERE document_id=?",
                        String.class,
                        doc.id());
        assertThat(before).isNotBlank();
        assertThat(knowledge.search("sync.targetPath", "2.0", 5, false).mode()).isEqualTo("HYBRID");
        assertThat(knowledge.search("sync.targetPath", "2.0", 5, true).mode()).isEqualTo("LEXICAL");
        EMBEDDING_FAILURE.set(true);
        knowledge.reindex(doc.id());
        assertThatThrownBy(this::drainKnowledge).hasMessageContaining("EMBEDDING_HTTP_503");
        assertThat(
                        db.queryForObject(
                                "SELECT vector_task_id FROM document_releases WHERE document_id=?",
                                String.class,
                                doc.id()))
                .isEqualTo(before);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM knowledge_tasks WHERE document_id=? AND"
                                        + " status='RETRY_WAIT'",
                                Integer.class,
                                doc.id()))
                .isEqualTo(1);
        assertThatThrownBy(() -> knowledge.search("sync.targetPath", "2.0", 5, false))
                .hasMessageContaining("EMBEDDING_HTTP_503");
    }

    /** 第二个向量写入失败时，已写入的暂存向量不可见，旧发布指针保持有效。 */
    @Test
    void partialIndexWriteFailurePreservesEveryOldVectorAndFingerprint() {
        KnowledgeDocument document =
                ingest("atomic-index.md", "2.0", "# 索引回滚\n" + "sync.targetPath ".repeat(100));
        String before =
                db.queryForObject(
                        "SELECT vector_task_id FROM document_releases WHERE document_id=?",
                        String.class,
                        document.id());
        AtomicInteger writes = new AtomicInteger();
        ElasticKnowledgeIndex failing =
                new ElasticKnowledgeIndex(JSON, RESOURCES.es, "", "", RESOURCES.prefix) {
                    /** 第二个 ES 写入失败，第一条真实暂存写入保持不可见。 */
                    @Override
                    public void putVector(
                            String index,
                            String taskId,
                            ChunkSource source,
                            double[] vector,
                            int epoch) {
                        if (writes.incrementAndGet() == 2) {
                            throw new IllegalStateException("INJECTED_INDEX_FAILURE");
                        }
                        super.putVector(index, taskId, source, vector, epoch);
                    }
                };
        ReflectionTestUtils.setField(ReflectionTestUtils.getField(library, "indexing"), "elastic", failing);
        try {
            knowledge.reindex(document.id());
            assertThatThrownBy(this::drainKnowledge).hasMessageContaining("INJECTED_INDEX_FAILURE");
            assertThat(writes).hasValue(2);
            assertThat(
                            db.queryForObject(
                                    "SELECT vector_task_id FROM document_releases WHERE"
                                            + " document_id=?",
                                    String.class,
                                    document.id()))
                    .isEqualTo(before);
            assertThat(knowledge.document(document.id()).embeddingKey())
                    .isEqualTo(document.embeddingKey());
            String index =
                    db.queryForObject(
                            "SELECT index_name FROM knowledge_tasks WHERE id=?",
                            String.class,
                            before);
            elastic.verify(index, "taskId", before, document.chunks());
            assertThat(knowledge.search("sync.targetPath", "2.0", 5, false).passages())
                    .allMatch(passage -> passage.batchId().equals(document.batchId()));
        } finally {
            ReflectionTestUtils.setField(ReflectionTestUtils.getField(library, "indexing"), "elastic", elastic);
        }
    }

    /** 验证 PDF 提取保留页码并拒绝没有文字的文件。 */
    @Test
    void pdfExtractionRetainsPageAndBlankPdfIsRejected() throws Exception {
        try (PDDocument pdf = new PDDocument();
                ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            pdf.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700);
                content.showText("sync.targetPath current setting");
                content.endText();
            }
            pdf.save(bytes);
            KnowledgeDocument doc =
                    knowledge.ingest("guide.pdf", "PDF guide", "2.0", bytes.toByteArray());
            drainKnowledge();
            assertThat(knowledge.content(doc.id()).toString()).contains("第 1 页", "sync.targetPath");
        }
        try (PDDocument pdf = new PDDocument();
                ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            pdf.addPage(new PDPage());
            pdf.save(bytes);
            assertThatThrownBy(
                            () -> {
                                knowledge.ingest("blank.pdf", "Blank", "2.0", bytes.toByteArray());
                                drainKnowledge();
                            })
                    .hasMessageContaining("NO_EXTRACTABLE_TEXT");
        }
    }

    /** 验证项目记忆必须经人员确认，删除后不再加载。 */
    @Test
    void memoryRequiresExplicitConfirmationAndSupportsDeletion() {
        assertThat(
                        http.postForEntity(
                                        "/api/memories",
                                        Map.of("content", "No restart", "confirmed", false),
                                        String.class)
                                .getStatusCode()
                                .value())
                .isEqualTo(400);
        ProjectMemory memory = memories.add("No restart without a change window", true);
        assertThat(memories.list()).containsExactly(memory);
        memories.delete(memory.id());
        assertThat(memories.list()).isEmpty();
    }

    /** 验证跨来源浏览器请求被拒绝且不会修改示例环境。 */
    @Test
    void localBrowserBoundaryRejectsForeignOrigin() {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin("https://example.com");
        assertThat(
                        http.exchange(
                                        "/api/demo/repair-config",
                                        HttpMethod.POST,
                                        new HttpEntity<>(Map.of(), headers),
                                        String.class)
                                .getStatusCode()
                                .value())
                .isEqualTo(403);
        assertThat(demo.operatorView().lastRequest().toString()).contains("httpStatus=410");
    }

    /** 验证 AgentScope 实际调用 MCP 与检索工具，并保存事件和最终结果。 */
    @Test
    void agentScopeUsesActualMcpAndRetrievalAndPersistsTrace() throws Exception {
        KnowledgeDocument doc =
                ingest(
                        "guide.md",
                        "2.0",
                        "# Configuration\nsync.targetPath migration: orders.path is ignored.");
        ingest("old.md", "1.0", "# Legacy\norders.path is the old configuration.");
        ProjectMemory memory = memories.add("请在变更窗口内处理", true);
        tool(
                "load_skill_through_path",
                "{\"skillId\":\"incident-triage_bundled\",\"path\":\"SKILL.md\"}");
        tool("get_app_info", "{}");
        tool("get_effective_config", "{}");
        tool("search_knowledge", "{\"query\":\"sync.targetPath\"}");
        complete("测试协议结果，引用 [" + doc.id() + ":1]。这是固定测试响应，不是模型质量证明。");
        DiagnosisRun run = await(runs.start("检查升级后订单失败", null, true).id());
        assertThat(run.status())
                .withFailMessage("%s; events=%s", run, runs.events(run.id(), 0))
                .isEqualTo("COMPLETED");
        assertThat(run.answer()).contains(doc.id());
        assertThat(run.inputTokens()).isGreaterThan(0);
        List<DiagnosisEvent> events = runs.events(run.id(), 0);
        assertThat(events)
                .extracting(DiagnosisEvent::kind)
                .contains("TOOL_CALL", "TOOL_RESULT", "KNOWLEDGE", "CONTEXT", "USAGE");
        assertThat(events.toString()).contains("\"versionFilter\":\"2.0\"");
        assertThat(REQUESTS.getFirst().toString())
                .contains(memory.content(), "get_app_info", "search_knowledge")
                .doesNotContain("execute_shell", "repair_config\"", "injection-2.0");
        assertThat(REQUESTS.getLast().toString()).contains("/v1/orders", doc.id(), "集成故障初诊");
        ArrayList<String> names = new ArrayList<String>();
        REQUESTS.getFirst()
                .path("tools")
                .forEach(t -> names.add(t.path("function").path("name").asText()));
        assertThat(names)
                .containsExactlyInAnyOrder(
                        "get_app_info",
                        "get_effective_config",
                        "get_recent_logs",
                        "get_downstream_health",
                        "search_knowledge",
                        "list_excel_datasets",
                        "query_excel",
                        "load_skill_through_path");
        memories.delete(memory.id());
        complete("新的会话测试响应");
        DiagnosisRun second = await(runs.start("重新检查", null, true).id());
        assertThat(second.status()).isEqualTo("COMPLETED");
        assertThat(REQUESTS.getLast().toString()).doesNotContain(memory.content());
    }

    /** 验证服务商调用失败保存为任务失败，不生成模拟回答。 */
    @Test
    void providerFailureIsPersistedAsFailure() throws Exception {
        DiagnosisRun run = await(runs.start("provider failure", null, true).id());
        assertThat(run.status()).isEqualTo("FAILED");
        assertThat(run.answer()).isEmpty();
        assertThat(run.errorCode()).isEqualTo("DIAGNOSIS_EXECUTION_FAILED");
    }

    /** 核对实际发送到本地夹具的模型、密钥和兼容参数属于当前服务商。 */
    @Test
    void selectedProvidersSendTheirOwnModelCredentialAndCompatibleOptionsOverRealHttp()
            throws Exception {
        for (String provider : List.of("deepseek", "openai")) {
            environment
                    .getPropertySources()
                    .addFirst(
                            new MapPropertySource(
                                    "provider-test",
                                    Map.of(
                                            "supportops.model.provider",
                                            provider,
                                            "supportops.model.name",
                                            "",
                                            "supportops.model.api-key",
                                            "",
                                            "supportops.model.api-key-env",
                                            provider.equals("deepseek")
                                                    ? "DEEPSEEK_API_KEY"
                                                    : "OPENAI_API_KEY",
                                            "DEEPSEEK_API_KEY",
                                            "test-deepseek",
                                            "OPENAI_API_KEY",
                                            "test-openai",
                                            "supportops.embedding.provider",
                                            "disabled")));
            complete("固定协议测试：服务商路由已验证，不代表真实模型效果。");
            DiagnosisRun run = await(runs.start("provider routing", null, true).id());
            assertThat(run.status())
                    .withFailMessage("%s; %s", run, runs.events(run.id(), 0))
                    .isEqualTo("COMPLETED");
            JsonNode body = REQUESTS.getLast();
            assertThat(AUTHORIZATIONS.getLast()).isEqualTo("Bearer test-" + provider);
            assertThat(body.path("model").asText())
                    .isEqualTo(provider.equals("deepseek") ? "deepseek-v4-flash" : "gpt-4.1-mini");
            if (provider.equals("deepseek")) {
                assertThat(body.path("max_tokens").asInt()).isEqualTo(2500);
                assertThat(body.has("max_completion_tokens")).isFalse();
                assertThat(body.path("thinking").path("type").asText()).isEqualTo("disabled");
            } else {
                assertThat(body.path("max_completion_tokens").asInt()).isEqualTo(2500);
                assertThat(body.has("max_tokens")).isFalse();
                assertThat(body.has("temperature")).isFalse();
                assertThat(body.has("thinking")).isFalse();
            }
            String status = http.getForObject("/api/status", String.class);
            assertThat(status).contains(provider).doesNotContain("test-deepseek", "test-openai");
            assertThat(runs.events(run.id(), 0).toString())
                    .contains("modelRoute", provider)
                    .doesNotContain("test-deepseek", "test-openai");
        }
    }

    /** 验证未注册写工具无法改变现场，工具拒绝仍可追踪。 */
    @Test
    void nonexistentWriteToolCannotMutateEnvironment() throws Exception {
        tool("repair_config", "{}");
        complete("固定协议测试响应：没有可用的写工具。");
        DiagnosisRun run = await(runs.start("tool boundary test", null, true).id());
        assertThat(run.status()).isEqualTo("COMPLETED");
        assertThat(demo.operatorView().lastRequest().toString()).contains("httpStatus=410");
        assertThat(runs.events(run.id(), 0).toString()).contains("repair_config", "error");
    }

    /** 验证内置资料可通过 HTTP 重复导入且不会重复保存。 */
    @Test
    void bundledDocumentsCanBeImportedThroughHttpWithoutDuplication() {
        assertThat(
                        http.postForEntity("/api/documents/import-examples", Map.of(), String.class)
                                .getStatusCode()
                                .value())
                .isEqualTo(200);
        assertThat(
                        http.postForEntity("/api/documents/import-examples", Map.of(), String.class)
                                .getStatusCode()
                                .value())
                .isEqualTo(200);
        drainKnowledge();
        assertThat(knowledge.documents()).hasSize(3);
        assertThat(knowledge.search("sync.targetPath", "2.0", 5, true).passages())
                .allMatch(p -> p.version().equals("2.0"));
    }

    /** 通过正式知识服务保存测试 Markdown 文档。 */
    private KnowledgeDocument ingest(String file, String version, String text) {
        KnowledgeDocument accepted =
                knowledge.ingest(file, file, version, text.getBytes(StandardCharsets.UTF_8));
        drainKnowledge();
        return knowledge.document(accepted.id());
    }

    /** 通过正式领取、执行与完成事务处理隔离队列，不模拟数据库结果。 */
    private void drainKnowledge() {
        IndexTaskEntity task;
        while ((task = knowledgeTasks.claim()) != null) {
            try {
                library.execute(task);
                knowledgeTasks.finish(task, null);
            } catch (RuntimeException exception) {
                knowledgeTasks.finish(task, exception);
                throw exception;
            }
        }
    }

    /** Agent 的 SQL 工具调用真实 Excel 数据表，结果保留修订、查询口径及精确值。 */
    @Test
    void agentTextToSqlUsesRegisteredDatasetAndPersistsSourceEvidence() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        EasyExcel.write(bytes)
                .head(List.of(List.of("地区"), List.of("金额")))
                .sheet("交易")
                .doWrite(
                        List.of(
                                List.of("上海", new BigDecimal("100.10")),
                                List.of("上海", new BigDecimal("20.20"))));
        KnowledgeViews.Upload upload =
                library.submit(
                        "agent-query.xlsx",
                        "合成交易",
                        "2.0",
                        null,
                        "",
                        null,
                        "TEST",
                        bytes.toByteArray(),
                        ImportOptions.defaults());
        drainKnowledge();
        String dataset =
                db.queryForObject(
                        "SELECT id FROM excel_datasets WHERE batch_id=? AND status='READY'",
                        String.class,
                        upload.batchId());
        tool("list_excel_datasets", "{}");
        tool(
                "query_excel",
                new ObjectMapper()
                        .writeValueAsString(
                                Map.of(
                                        "datasetId",
                                        dataset,
                                        "sql",
                                        "SELECT SUM(c_1) AS total FROM data WHERE c_0='上海'")));
        complete("本地协议测试：上海交易汇总为 120.30，来源为合成交易表。");
        DiagnosisRun run = await(runs.start("查询上海交易金额合计", null, true).id());
        assertThat(run.status()).isEqualTo("COMPLETED");
        DiagnosisEvent evidence =
                runs.events(run.id(), 0).stream()
                        .filter(event -> event.kind().equals("SQL_RESULT"))
                        .findFirst()
                        .orElseThrow();
        assertThat(evidence.content().toString())
                .contains(dataset, upload.versionId(), "120.3000000000", "SUM(c_1)");
        assertThat(REQUESTS.getLast().toString()).contains("120.3000000000");
    }

    /** 真实 multipart 入口拒绝超过 20 MiB 的文件，尚未受理时不生成业务记录。 */
    @Test
    void multipartEnforcesTwentyMegabyteFileLimit() {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add(
                "file",
                new ByteArrayResource(new byte[20 * 1024 * 1024 + 1]) {
                    /** multipart 使用合成文件名，不依赖磁盘文件。 */
                    @Override
                    public String getFilename() {
                        return "over-limit.md";
                    }
                });
        body.add("title", "合成上限测试");
        body.add("version", "2.0");
        ResponseEntity<String> response =
                http.postForEntity("/api/documents/uploads", body, String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(413);
        assertThat(response.getBody()).contains("FILE_TOO_LARGE");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM document_uploads", Integer.class))
                .isZero();
    }

    /** 真实 Cookie 登录、角色拒绝、跨用户隔离及上传归属均通过 HTTP 与独立 SQL 核对。 */
    @Test
    void userRolesAndRecordOwnershipUseAuthenticatedIdentity() throws Exception {
        assertThat(authRequest("GET", "/api/runs", null, null).statusCode()).isEqualTo(401);
        assertThat(authRequest("GET", "/v3/api-docs", null, null).statusCode()).isEqualTo(401);
        assertThat(authRequest("POST", "/api/auth/setup", null,
                "{\"username\":\"another_admin\",\"password\":\"synthetic-password\"}").statusCode()).isEqualTo(409);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String username = "reader_" + suffix;
        String userBody = JSON.writeValueAsString(Map.of("username", username, "password", "synthetic-reader-password", "role", "USER"));
        HttpResponse<String> created = authRequest("POST", "/api/users", adminCookie, userBody);
        assertThat(created.statusCode()).isEqualTo(200);
        String userId = JSON.readTree(created.body()).path("id").asText();
        assertThat(created.body()).doesNotContain("password", "synthetic-reader-password");
        assertThat(db.queryForObject("SELECT password_hash FROM app_users WHERE id=?", String.class, userId))
                .startsWith("600000:").doesNotContain("synthetic-reader-password");
        assertThat(authRequest("POST", "/api/users", adminCookie, userBody).statusCode()).isEqualTo(409);
        assertThat(authRequest("POST", "/api/auth/login", null,
                JSON.writeValueAsString(Map.of("username", username, "password", "synthetic-wrong-password"))).statusCode()).isEqualTo(401);
        HttpResponse<String> login = authRequest("POST", "/api/auth/login", null,
                JSON.writeValueAsString(Map.of("username", username.toUpperCase(Locale.ROOT), "password", "synthetic-reader-password", "role", "ADMIN")));
        assertThat(login.statusCode()).isEqualTo(200);
        String cookieHeader = login.headers().firstValue("Set-Cookie").orElseThrow();
        assertThat(cookieHeader).contains("HttpOnly", "SameSite=Strict");
        String cookie = cookieHeader.split(";", 2)[0];
        assertThat(JSON.readTree(login.body()).path("role").asText()).isEqualTo("USER");
        for (String path : List.of("/api/users", "/api/documents", "/api/documents/uploads", "/api/memories", "/api/demo", "/api/model-settings", "/api/usage", "/v3/api-docs")) {
            assertThat(authRequest("GET", path, cookie, null).statusCode()).as(path).isEqualTo(403);
        }
        assertThat(authRequest("POST", "/api/users", cookie, userBody).statusCode()).isEqualTo(403);
        complete("合成用户归属验证");
        HttpResponse<String> started = authRequest("POST", "/api/runs", cookie,
                "{\"question\":\"验证归属\",\"lexicalOnly\":true,\"userId\":\"forged\"}");
        assertThat(started.statusCode()).isEqualTo(200);
        String runId = JSON.readTree(started.body()).path("id").asText();
        DiagnosisRun completed = await(runId);
        assertThat(completed.userId()).isEqualTo(userId);
        assertThat(db.queryForObject("SELECT user_id FROM runs WHERE id=?", String.class, runId)).isEqualTo(userId);
        assertThat(authRequest("GET", "/api/runs/" + runId + "/events", cookie, null).statusCode()).isEqualTo(200);
        assertThat(authRequest("GET", "/api/runs/" + runId + "/stream", cookie, null).statusCode()).isEqualTo(200);
        assertThat(authRequest("GET", "/api/runs", cookie, null).body()).contains(runId);
        HttpResponse<String> secondUser = authRequest("POST", "/api/users", adminCookie,
                JSON.writeValueAsString(Map.of("username", "other_" + suffix, "password", "synthetic-other-password", "role", "USER")));
        assertThat(secondUser.statusCode()).isEqualTo(200);
        HttpResponse<String> secondLogin = authRequest("POST", "/api/auth/login", null,
                JSON.writeValueAsString(Map.of("username", "other_" + suffix, "password", "synthetic-other-password")));
        String otherCookie = secondLogin.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
        assertThat(authRequest("GET", "/api/runs", otherCookie, null).body()).isEqualTo("[]");
        for (String ending : List.of("", "/events", "/stream")) {
            assertThat(authRequest("GET", "/api/runs/" + runId + ending, otherCookie, null).statusCode()).isEqualTo(404);
        }
        assertThat(authRequest("POST", "/api/runs/" + runId + "/cancel", otherCookie, "{}").statusCode()).isEqualTo(404);
        assertThat(authRequest("POST", "/api/runs/" + runId + "/archive", otherCookie, "{}").statusCode()).isEqualTo(404);
        assertThat(db.queryForObject("SELECT archived_at FROM runs WHERE id=?", String.class, runId)).isNull();
        assertThat(authRequest("POST", "/api/runs", otherCookie,
                JSON.writeValueAsString(Map.of("question", "越权续聊", "sessionId", completed.sessionId()))).statusCode()).isEqualTo(404);
        assertThat(authRequest("GET", "/api/runs/" + runId, adminCookie, null).statusCode()).isEqualTo(200);
        assertThat(authRequest("GET", "/api/usage/sessions/" + completed.sessionId(), adminCookie, null).body()).contains(userId);
        // 旧记录保留空归属：管理员可见，普通用户不可见。
        String usageBeforeArchive = authRequest("GET", "/api/usage?from=2026-09-01&to=2026-09-30", adminCookie, null).body();
        assertThat(authRequest("POST", "/api/runs/" + runId + "/archive", cookie, "{}").statusCode()).isEqualTo(200);
        assertThat(authRequest("POST", "/api/runs/" + runId + "/archive", cookie, "{}").statusCode()).isEqualTo(200);
        assertThat(authRequest("GET", "/api/runs", cookie, null).body()).doesNotContain(runId);
        assertThat(authRequest("GET", "/api/runs", adminCookie, null).body()).doesNotContain(runId);
        assertThat(authRequest("GET", "/api/runs/" + runId + "/events", cookie, null).statusCode()).isEqualTo(200);
        assertThat(authRequest("GET", "/api/usage?from=2026-09-01&to=2026-09-30", adminCookie, null).body()).isEqualTo(usageBeforeArchive);
        assertThat(authRequest("POST", "/api/runs", cookie,
                JSON.writeValueAsString(Map.of("question", "归档后续聊", "sessionId", completed.sessionId(), "lexicalOnly", true)))
                .statusCode()).isEqualTo(409);
        db.update("UPDATE runs SET user_id=NULL WHERE id=?", runId);
        assertThat(authRequest("GET", "/api/runs/" + runId, cookie, null).statusCode()).isEqualTo(404);
        assertThat(authRequest("GET", "/api/runs/" + runId, adminCookie, null).statusCode()).isEqualTo(200);
        MultiValueMap<String, Object> uploadBody = new LinkedMultiValueMap<>();
        uploadBody.add("file", new ByteArrayResource("合成上传归属".getBytes(StandardCharsets.UTF_8)) {
            /** 使用合成 Markdown 文件名。 */
            @Override
            public String getFilename() {
                return "ownership.md";
            }
        });
        uploadBody.add("title", "归属验证");
        uploadBody.add("version", "2.0");
        uploadBody.add("userId", userId);
        ResponseEntity<String> upload = http.postForEntity("/api/documents/uploads", uploadBody, String.class);
        assertThat(upload.getStatusCode().value()).isEqualTo(202);
        JsonNode uploadView = JSON.readTree(upload.getBody());
        String adminId = JSON.readTree(authRequest("GET", "/api/auth/me", adminCookie, null).body()).path("id").asText();
        assertThat(uploadView.path("userId").asText()).isEqualTo(adminId);
        assertThat(db.queryForObject("SELECT user_id FROM document_uploads WHERE id=?", String.class,
                uploadView.path("id").asText())).isEqualTo(adminId);
        assertThat(authRequest("POST", "/api/auth/logout", cookie, "{}").statusCode()).isEqualTo(200);
        assertThat(authRequest("GET", "/api/runs", cookie, null).statusCode()).isEqualTo(401);
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> noCsrf = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/logout"))
                    .header("Cookie", otherCookie).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(noCsrf.statusCode()).isEqualTo(403);
        }
    }

    /** 使用独立 HTTP 客户端显式指定 Cookie，避免管理员测试拦截器影响越权断言。 */
    private HttpResponse<String> authRequest(String method, String path, String cookie, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("X-SupportOps-Request", "1").timeout(Duration.ofSeconds(20));
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    /** 向模型协议夹具追加一条固定最终文本事件。 */
    private static void complete(String text) {
        COMPLETIONS.add(Map.of("role", "assistant", "content", text));
    }

    /** 向模型协议夹具追加一次指定工具调用。 */
    private static void tool(String name, String arguments) {
        COMPLETIONS.add(
                Map.of(
                        "role",
                        "assistant",
                        "tool_calls",
                        List.of(
                                Map.of(
                                        "index",
                                        0,
                                        "id",
                                        "call-" + UUID.randomUUID(),
                                        "type",
                                        "function",
                                        "function",
                                        Map.of("name", name, "arguments", arguments)))));
    }

    /** 在有限时间内等待任务进入终态，超时即测试失败。 */
    private DiagnosisRun await(String id) throws Exception {
        for (int i = 0; i < 400; i++) {
            DiagnosisRun run = runs.get(id);
            if (!Set.of("QUEUED", "RUNNING").contains(run.status())) {
                return run;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Run did not finish: " + runs.get(id));
    }
}
