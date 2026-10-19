package io.supportops.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.supportops.persistence.KnowledgeTestResources;
import io.supportops.agent.service.RunService;
import io.supportops.feishu.FeishuProperties;
import io.supportops.feishu.dto.FeishuIncomingMessage;
import io.supportops.feishu.mapper.FeishuBindingMapper;
import io.supportops.feishu.mapper.FeishuInboxMapper;
import io.supportops.feishu.mapper.FeishuOutboxMapper;
import io.supportops.feishu.service.FeishuBindingService;
import io.supportops.feishu.service.FeishuService;
import io.supportops.feishu.service.impl.FeishuBindingServiceImpl;
import io.supportops.feishu.service.impl.FeishuServiceImpl;
import io.supportops.feishu.vo.FeishuViews;
import io.supportops.project.service.ProjectService;
import io.supportops.user.service.UserService;
import java.time.Clock;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import io.supportops.knowledge.dto.ImportOptions;
import io.supportops.knowledge.entity.IndexTaskEntity;
import io.supportops.knowledge.service.DocumentLibraryService;
import io.supportops.knowledge.service.KnowledgeTaskService;
import io.supportops.knowledge.service.KnowledgeService;
import io.supportops.knowledge.mapper.KnowledgeCatalogMapper;
import io.supportops.knowledge.mapper.DocumentReleaseMapper;
import io.supportops.knowledge.mapper.IndexTaskMapper;
import io.supportops.knowledge.service.EmbeddingClient;
import io.supportops.knowledge.service.ElasticKnowledgeIndex;
import io.supportops.knowledge.service.impl.KnowledgeServiceImpl;
import io.supportops.knowledge.service.impl.OnnxPassageReranker;
import io.supportops.knowledge.vo.KnowledgeSearchResult;
import io.supportops.integration.service.DataPolicyService;
import org.springframework.mock.env.MockEnvironment;
import io.supportops.knowledge.service.ExcelDataService;
import io.supportops.knowledge.vo.KnowledgeViews;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CompletableFuture;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.Sheet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 真实隔离存储和 HTTP 认证链；两个 Java 源为独立进程，模型为明确的协议夹具。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=", "supportops.rerank.enabled=false", "supportops.model.timeout-seconds=30"})
class EnterpriseIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final KnowledgeTestResources RESOURCES = new KnowledgeTestResources();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final AtomicInteger MODEL_CALLS = new AtomicInteger();
    private static final AtomicBoolean FEISHU_TOOL_EXCHANGE = new AtomicBoolean();
    private static final Path DIRECTORY;
    private static final Path MASTER;
    private static final HttpServer MODEL;
    private static final Sample FIRST;
    private static final Sample SECOND;
    static {
        try {
            DIRECTORY = Files.createTempDirectory("supportops-enterprise-it-");
            MASTER = DIRECTORY.resolve("master.json");
            Files.writeString(MASTER, JSON.writeValueAsString(Map.of("deploymentId", UUID.randomUUID().toString(),
                    "activeKeyId", "v1", "keys", Map.of("v1", Base64.getEncoder().encodeToString(entropy())))));
            FIRST = sample("erp-one"); SECOND = sample("erp-two");
            MODEL = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            MODEL.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            MODEL.createContext("/v1/chat/completions", exchange -> {
                MODEL_CALLS.incrementAndGet();
                JsonNode payload = JSON.readTree(exchange.getRequestBody().readAllBytes());
                boolean hasTool = false;
                for (JsonNode message : payload.path("messages")) {
                    if ("tool".equals(message.path("role").asText())) { hasTool = true; }
                }
                String part = "{\"id\":\"fixture\",\"object\":\"chat.completion.chunk\",\"model\":\"fixture\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"合成协议回答；真实诊断效果未验证。\"}}]}";
                String end = "{\"id\":\"fixture\",\"object\":\"chat.completion.chunk\",\"model\":\"fixture\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":5,\"total_tokens\":25}}";
                if (FEISHU_TOOL_EXCHANGE.get() && !hasTool) {
                    ObjectNode delta = JSON.createObjectNode().put("role", "assistant");
                    ObjectNode tool = JSON.createObjectNode().put("index", 0).put("id", "feishu_fixture_tool").put("type", "function");
                    tool.set("function", JSON.createObjectNode().put("name", "get_app_info").put("arguments", "{}"));
                    delta.set("tool_calls", JSON.createArrayNode().add(tool));
                    ObjectNode choice = JSON.createObjectNode().put("index", 0);
                    choice.set("delta", delta);
                    ObjectNode frame = JSON.createObjectNode().put("id", "fixture").put("object", "chat.completion.chunk").put("model", "fixture");
                    frame.set("choices", JSON.createArrayNode().add(choice));
                    part = JSON.writeValueAsString(frame);
                    end = end.replace("stop", "tool_calls");
                }
                byte[] bytes = ("data: " + part + "\n\ndata: " + end + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
            });
            MODEL.start();
        } catch (Exception exception) { throw new ExceptionInInitializerError(exception); }
    }
    /** 为本测试随机数据库和存储空间注册专用配置，关闭付费外部服务。 */
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        RESOURCES.properties(registry);
        registry.add("supportops.settings.file", () -> DIRECTORY.resolve("settings.json").toString());
        registry.add("supportops.enterprise.master-key-file", MASTER::toString);
        registry.add("supportops.model.base-url", () -> "http://127.0.0.1:" + MODEL.getAddress().getPort() + "/v1");
        registry.add("supportops.model.api-key", () -> "synthetic-protocol-model-key");
        registry.add("supportops.model.name", () -> "protocol-fixture");
        registry.add("supportops.embedding.provider", () -> "disabled");
    }
    @LocalServerPort private int port;
    @Autowired private JdbcTemplate database;
    @Autowired private ApplicationContext context;
    @Autowired private DocumentLibraryService library;
    @Autowired private KnowledgeTaskService knowledgeTasks;
    @Autowired private KnowledgeService knowledge;
    @Autowired private ExcelDataService excel;
    private String cookie;
    /** 从资源管理贯通连接、应用身份、固定请求及真实执行。 */
    @Test void independentSourcesAndAtomicOpenApi() throws Exception {
        JsonNode administrator = browser("/api/auth/setup", object("username", "enterprise_admin", "password", "synthetic-password-123"), 200);
        assertThat(administrator.path("role").asText()).isEqualTo("ADMIN");
        managementBoundary();
        JsonNode project = resource("projects", null, "enterprise", "合成企业项目", null, null);
        JsonNode systemA = resource("systems", project.path("id").asText(), "erp-one", "第一套系统", "example-erp", null);
        JsonNode systemB = resource("systems", project.path("id").asText(), "erp-two", "第二套系统", "example-erp", null);
        JsonNode environmentA = resource("environments", systemA.path("id").asText(), "test", "第一套测试环境", null, "2.0");
        JsonNode environmentB = resource("environments", systemB.path("id").asText(), "test", "第二套测试环境", null, "2.0");
        JsonNode connectorA = connect(environmentA, FIRST);
        connect(environmentB, SECOND);
        String environmentId = environmentA.path("id").asText();
        JsonNode application = application(project.path("id").asText(), environmentId, "ticket-system");
        ObjectNode issue = object("requestId", UUID.randomUUID().toString(), "applicationId", application.path("id").asText(), "expiresInDays", 30, "rotate", false);
        JsonNode key = browser("/api/enterprise/applications/keys", issue, 200);
        String token = key.path("secret").asText(); assertThat(token).startsWith("sop_");
        JsonNode replayKey = browser("/api/enterprise/applications/keys", issue, 200);
        assertThat(replayKey.has("secret")).isFalse(); assertThat(replayKey.path("key").path("id")).isEqualTo(key.path("key").path("id"));
        assertThat(database.queryForObject("SELECT COUNT(*) FROM application_access_keys", Integer.class)).isEqualTo(1);
        assertThat(database.queryForObject("SELECT secret_hash FROM application_access_keys LIMIT 1", String.class)).doesNotContain(token);
        assertThat(database.queryForObject("SELECT ciphertext FROM credentials WHERE id=?", String.class,
                connectorA.path("credentialId").asText())).doesNotContain(FIRST.token());
        JsonNode targets = machine("GET", "/open-api/v1/environments", token, null, 200);
        assertThat(targets.size()).isEqualTo(1); assertThat(targets.get(0).path("environmentId").asText()).isEqualTo(environmentId);
        String fixedId = UUID.randomUUID().toString();
        ObjectNode command = object("requestId", fixedId, "environmentId", environmentId, "question", "  检查合成依赖  ", "lexicalOnly", true);
        JsonNode accepted = machine("POST", "/open-api/v1/diagnoses", token, command, 202);
        String runId = accepted.path("runId").asText(); assertThat(runId).isNotEqualTo(fixedId);
        JsonNode replay = machine("POST", "/open-api/v1/diagnoses", token, command, 200);
        assertThat(replay.path("runId").asText()).isEqualTo(runId);
        machine("GET", "/open-api/v1/requests/" + fixedId, token, null, 200);
        command.put("question", "不同问题"); machine("POST", "/open-api/v1/diagnoses", token, command, 409);
        assertThat(database.queryForObject("SELECT COUNT(*) FROM integration_requests", Integer.class)).isEqualTo(1);
        assertThat(database.queryForObject("SELECT COUNT(*) FROM runs WHERE application_id=?", Integer.class, application.path("id").asText())).isEqualTo(1);
        assertThat(database.queryForObject("SELECT COUNT(*) FROM run_target_snapshots WHERE run_id=?", Integer.class, runId)).isEqualTo(1);
        assertThat(database.queryForObject("SELECT user_id IS NULL FROM runs WHERE id=?", Boolean.class, runId)).isTrue();
        JsonNode terminal = waitFor(runId, token); assertThat(terminal.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(terminal.path("usage").path("inputTokens").asLong()).isEqualTo(20);
        machine("GET", "/open-api/v1/diagnoses/" + runId + "/result", token, null, 200);
        JsonNode events = machine("GET", "/open-api/v1/diagnoses/" + runId + "/events?limit=2", token, null, 200);
        assertThat(events.path("events").size()).isLessThanOrEqualTo(2); assertThat(events.toString()).doesNotContain(token).doesNotContain(FIRST.token());
        machine("GET", "/open-api/v1/diagnoses/" + runId + "/events?after=-1", token, null, 400);
        machine("GET", "/open-api/v1/diagnoses/" + runId + "/evidence/1", token, null, 404);
        JsonNode otherApplication = application(project.path("id").asText(), environmentId, "other-ticket-system");
        JsonNode otherKey = browser("/api/enterprise/applications/keys", object("requestId", UUID.randomUUID().toString(),
                "applicationId", otherApplication.path("id").asText(), "expiresInDays", 1, "rotate", false), 200);
        machine("GET", "/open-api/v1/diagnoses/" + runId, otherKey.path("secret").asText(), null, 404);
        ObjectNode unauthorized = object("requestId", UUID.randomUUID().toString(), "environmentId", environmentB.path("id").asText(), "question", "合成问题");
        machine("POST", "/open-api/v1/diagnoses", token, unauthorized, 403);
        ObjectNode unknown = object("requestId", UUID.randomUUID().toString(), "environmentId", environmentId, "question", "合成问题", "owner", "forged");
        machine("POST", "/open-api/v1/diagnoses", token, unknown, 400);
        machine("GET", "/open-api/v1/environments", null, null, 401);
        JsonNode schema = browser("/v3/api-docs", null, 200);
        assertThat(schema.path("paths").has("/open-api/v1/diagnoses")).isTrue();
        assertThat(schema.path("components").path("securitySchemes").has("ApplicationBearer")).isTrue();
        responseLoss(token, application.path("id").asText(), environmentId);
        ordinaryUser(project.path("id").asText(), environmentId, environmentB.path("id").asText(), runId);
        scopedKnowledge(project.path("id").asText(), systemA.path("id").asText(), systemB.path("id").asText(), environmentId);
        enterpriseFeishuDefault(administrator.path("id").asText(), environmentB.path("id").asText());
        // 现场版本仍2.0，管理员更新预期为3.0；身份失败不能产生模型请求。
        int before = MODEL_CALLS.get();
        database.update("UPDATE system_environments SET product_version='3.0' WHERE id=?", environmentId);
        ObjectNode drift = object("requestId", UUID.randomUUID().toString(), "environmentId", environmentId, "question", "核查版本漂移", "lexicalOnly", true);
        JsonNode driftAccepted = machine("POST", "/open-api/v1/diagnoses", token, drift, 202);
        JsonNode failed = waitFor(driftAccepted.path("runId").asText(), token);
        assertThat(failed.path("errorCode").asText()).isEqualTo("TARGET_IDENTITY_MISMATCH"); assertThat(MODEL_CALLS.get()).isEqualTo(before);
        browser("/api/enterprise/applications/keys/revoke", object("requestId", UUID.randomUUID().toString(), "keyId", key.path("key").path("id").asText()), 200);
        machine("GET", "/open-api/v1/diagnoses/" + runId, token, null, 401);
        // 恢复代次改变后，历史应用仍保留；重新批准前签发必须拒绝，不能返回不可用的新秘密。
        int keysBefore = database.queryForObject("SELECT COUNT(*) FROM application_access_keys", Integer.class);
        database.update("UPDATE deployment_runtime_guard SET restore_generation=restore_generation+1 WHERE id=1");
        String restoredRequest = UUID.randomUUID().toString();
        JsonNode restoredIssue = browser("/api/enterprise/applications/keys", object("requestId", restoredRequest,
                "applicationId", application.path("id").asText(), "expiresInDays", 1, "rotate", false), 409);
        assertThat(restoredIssue.path("error").asText()).isEqualTo("APPLICATION_DISABLED");
        assertThat(database.queryForObject("SELECT COUNT(*) FROM application_access_keys", Integer.class)).isEqualTo(keysBefore);
        assertThat(database.queryForObject("SELECT COUNT(*) FROM integration_management_requests WHERE request_id=?", Integer.class, restoredRequest)).isZero();
    }
    /** 使用合成平台收发边界贯通真实企业默认目标、收件去重、正式诊断与持久化回复，不连接飞书平台。 */
    private void enterpriseFeishuDefault(String userId, String environmentId) throws Exception {
        FeishuProperties properties = new FeishuProperties(true, "cli_enterprise_fixture", "synthetic-feishu-secret", "tenant_enterprise_fixture");
        Clock clock = Clock.systemUTC();
        FeishuBindingService bindings = new FeishuBindingServiceImpl(context.getBean(FeishuBindingMapper.class),
                context.getBean(UserService.class), properties, clock, context.getBean(ProjectService.class));
        AtomicInteger sent = new AtomicInteger();
        FeishuService channel = new FeishuServiceImpl(context.getBean(FeishuInboxMapper.class), context.getBean(FeishuOutboxMapper.class),
                bindings, context.getBean(RunService.class), (openId, content, requestId) -> "om_enterprise_reply_" + sent.incrementAndGet(),
                new TransactionTemplate(context.getBean(PlatformTransactionManager.class)), properties, clock);
        FeishuViews.BindingCode code = bindings.issue(userId, environmentId);
        channel.receive(new FeishuIncomingMessage(properties.appId(), properties.tenantKey(), "om_enterprise_bind", "ou_enterprise_fixture",
                "user", "p2p", "text", code.command(), clock.millis()));
        assertThat(database.queryForObject("SELECT default_environment_id FROM feishu_bindings WHERE id=?", String.class, code.id())).isEqualTo(environmentId);
        FEISHU_TOOL_EXCHANGE.set(true);
        try {
            FeishuIncomingMessage question = new FeishuIncomingMessage(properties.appId(), properties.tenantKey(), "om_enterprise_question", "ou_enterprise_fixture",
                    "user", "p2p", "text", "核对默认现场身份；问题文本提到source-one生产环境不能改变管理员指定的目标。", clock.millis());
            channel.receive(question);
            channel.receive(question);
            String runId = null;
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (System.nanoTime() < deadline) {
                channel.process();
                runId = database.queryForObject("SELECT id FROM feishu_inbox WHERE message_id='om_enterprise_question'", String.class);
                if (runId != null && "FINISHED".equals(database.queryForObject("SELECT state FROM feishu_inbox WHERE message_id='om_enterprise_question'", String.class))) {
                    break;
                }
                Thread.sleep(100);
            }
            assertThat(runId).isNotBlank();
            assertThat(database.queryForObject("SELECT status FROM runs WHERE id=?", String.class, runId)).isEqualTo("COMPLETED");
            assertThat(database.queryForObject("SELECT environment_id FROM run_target_snapshots WHERE run_id=?", String.class, runId)).isEqualTo(environmentId);
            assertThat(database.queryForObject("SELECT COUNT(*) FROM feishu_inbox WHERE message_id='om_enterprise_question'", Integer.class)).isEqualTo(1);
            JsonNode events = browser("/api/runs/" + runId + "/events", null, 200);
            assertThat(events.toString()).contains("SOURCE_EVIDENCE", "erp-two");
            assertThat(sent).hasValue(3);
            assertThat(database.queryForObject("SELECT COUNT(*) FROM feishu_outbox WHERE state='SENT'", Integer.class)).isEqualTo(3);
        } finally {
            FEISHU_TOOL_EXCHANGE.set(false);
        }
    }
    /** 审计写入故障必须连同主体和回执回滚，并发旧版本只允许一个成功。 */
    private void managementBoundary() throws Exception {
        String fixed = UUID.randomUUID().toString();
        faultCommand("CREATE TRIGGER enterprise_audit_failure BEFORE INSERT ON integration_audit_events FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='SYNTHETIC_AUDIT_FAILURE'");
        try {
            browser("/api/enterprise/projects", object("requestId", fixed, "code", "rollback-probe", "name", "回滚探针"), 500);
            assertThat(database.queryForObject("SELECT COUNT(*) FROM projects WHERE code='rollback-probe'", Integer.class)).isZero();
            assertThat(database.queryForObject("SELECT COUNT(*) FROM integration_management_requests WHERE request_id=?", Integer.class, fixed)).isZero();
        } finally { faultCommand("DROP TRIGGER enterprise_audit_failure"); }
        JsonNode project = browser("/api/enterprise/projects", object("requestId", fixed, "code", "rollback-probe", "name", "回滚探针"), 200);
        browser("/api/enterprise/projects", object("requestId", UUID.randomUUID().toString(), "code", "rollback-probe", "name", "重复编码"), 409);
        ObjectNode first = object("requestId", UUID.randomUUID().toString(), "id", project.path("id").asText(), "code", "rollback-probe", "name", "并发一", "status", "ACTIVE", "expectedVersion", 1);
        ObjectNode second = first.deepCopy();second.put("requestId", UUID.randomUUID().toString());second.put("name", "并发二");
        List<CompletableFuture<HttpResponse<String>>> requests = List.of(first, second).stream().map(body -> HTTP.sendAsync(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/enterprise/projects"))
                        .header("Cookie", cookie).header("Content-Type", "application/json").header("X-SupportOps-Request", "1")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString())).toList();
        List<Integer> codes = requests.stream().map(CompletableFuture::join).map(HttpResponse::statusCode).sorted().toList();
        assertThat(codes).containsExactly(200,409);
        assertThat(database.queryForObject("SELECT version FROM projects WHERE id=?", Integer.class, project.path("id").asText())).isEqualTo(2);
        browser("/api/enterprise/projects", object("requestId", UUID.randomUUID().toString(), "id", project.path("id").asText(), "code", "rollback-probe", "name", "保留历史", "status", "ARCHIVED", "expectedVersion", 2), 200);
        browser("/api/enterprise/projects", object("requestId", UUID.randomUUID().toString(), "id", project.path("id").asText(), "code", "rollback-probe", "name", "不可恢复", "status", "ACTIVE", "expectedVersion", 3), 409);
        assertThat(database.queryForObject("SELECT status FROM projects WHERE id=?", String.class, project.path("id").asText())).isEqualTo("ARCHIVED");
    }
    /** 仅用隔离管理员身份在本随机库建立和移除失败探针，不改变服务器安全设置。 */
    private void faultCommand(String statement) throws Exception {
        try (Connection connection = DriverManager.getConnection(RESOURCES.url(), RESOURCES.admin, RESOURCES.adminPassword);
                Statement sql = connection.createStatement()) { sql.execute(statement); }
    }
    /** 上游实际提交202之后切断受理连接；按同键查询和重放必须仍为一个真实任务。 */
    private void responseLoss(String token, String applicationId, String environmentId) throws Exception {
        AtomicBoolean committed = new AtomicBoolean();
        HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        proxy.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        proxy.createContext("/open-api/v1/diagnoses", exchange -> {
            try {
                byte[] body = exchange.getRequestBody().readAllBytes();
                HttpRequest forwarded = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/open-api/v1/diagnoses"))
                        .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
                HttpResponse<String> accepted = HTTP.send(forwarded, HttpResponse.BodyHandlers.ofString());
                committed.set(accepted.statusCode() == 202);
            } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        proxy.start();
        String requestId = UUID.randomUUID().toString();
        ObjectNode command = object("requestId", requestId, "environmentId", environmentId, "question", "提交后断线的合成任务", "lexicalOnly", true);
        int before = MODEL_CALLS.get();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + proxy.getAddress().getPort() + "/open-api/v1/diagnoses"))
                    .timeout(Duration.ofSeconds(15)).POST(HttpRequest.BodyPublishers.ofString(command.toString())).build();
            assertThatThrownBy(() -> HTTP.send(request, HttpResponse.BodyHandlers.ofString())).isInstanceOf(IOException.class);
            assertThat(committed.get()).isTrue();
            JsonNode receipt = machine("GET", "/open-api/v1/requests/" + requestId, token, null, 200);
            JsonNode replay = machine("POST", "/open-api/v1/diagnoses", token, command, 200);
            assertThat(replay.path("runId")).isEqualTo(receipt.path("runId"));
            assertThat(waitFor(receipt.path("runId").asText(), token).path("status").asText()).isEqualTo("COMPLETED");
            assertThat(database.queryForObject("SELECT COUNT(*) FROM integration_requests WHERE application_id=? AND request_id=?", Integer.class, applicationId, requestId)).isEqualTo(1);
            assertThat(MODEL_CALLS.get()).isEqualTo(before + 1);
        } finally { proxy.stop(0); }
    }
    /** 真实账号、Cookie、成员关系和环境授权缺一不可，撤销后本人历史也重新核对。 */
    private void ordinaryUser(String project, String environment, String otherEnvironment, String applicationRun) throws Exception {
        String adminCookie = cookie;
        JsonNode user = browser("/api/users", object("username", "enterprise_user", "password", "synthetic-password-456", "role", "USER"), 200);
        cookie = null;
        browser("/api/auth/login", object("username", "enterprise_user", "password", "synthetic-password-456"), 200);
        String userCookie = cookie;
        assertThat(browser("/api/diagnosis-targets", null, 200).toString()).doesNotContain(environment);
        browser("/api/enterprise/resources", null, 403);
        browser("/api/runs/" + applicationRun, null, 404);
        cookie = adminCookie;
        browser("/api/enterprise/project-members", object("requestId", UUID.randomUUID().toString(), "resourceId", project, "userId", user.path("id").asText(), "granted", true), 200);
        cookie = userCookie;
        assertThat(browser("/api/diagnosis-targets", null, 200).toString()).doesNotContain(environment);
        cookie = adminCookie;
        browser("/api/enterprise/environment-grants", object("requestId", UUID.randomUUID().toString(), "resourceId", environment, "userId", user.path("id").asText(), "granted", true), 200);
        cookie = userCookie;
        assertThat(browser("/api/diagnosis-targets", null, 200).toString()).contains(environment).doesNotContain(otherEnvironment);
        browser("/api/runs", object("question", "未授权环境", "sessionId", UUID.randomUUID().toString(), "lexicalOnly", true, "environmentId", otherEnvironment), 404);
        JsonNode own = browser("/api/runs", object("question", "人员合成任务", "sessionId", UUID.randomUUID().toString(), "lexicalOnly", true, "environmentId", environment), 200);
        String run = own.path("id").asText();
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline && List.of("RUNNING", "QUEUED").contains(browser("/api/runs/" + run, null, 200).path("status").asText())) { Thread.sleep(100); }
        assertThat(database.queryForObject("SELECT status FROM runs WHERE id=?", String.class, run)).isEqualTo("COMPLETED");
        cookie = adminCookie;
        browser("/api/enterprise/environment-grants", object("requestId", UUID.randomUUID().toString(), "resourceId", environment, "userId", user.path("id").asText(), "granted", false), 200);
        cookie = userCookie; browser("/api/runs/" + run, null, 404);
        cookie = adminCookie;
    }
    /** 同字节不同系统不能误去重；实际MinIO、ES、Excel SQL和三层约束均核对范围。 */
    private void scopedKnowledge(String project, String system, String otherSystem, String environment) throws Exception {
        byte[] bytes = "# 范围验收\n支付配置 scopecanary 支付接口要求可审阅来源。".getBytes(StandardCharsets.UTF_8);
        KnowledgeViews.Upload first = library.submitScoped(system, "scope.md", "范围资料", "2.0", null, null, UUID.randomUUID().toString(), "TEST", bytes, ImportOptions.defaults());
        KnowledgeViews.Upload second = library.submitScoped(otherSystem, "scope.md", "范围资料", "2.0", null, null, UUID.randomUUID().toString(), "TEST", bytes, ImportOptions.defaults());
        assertThat(first.documentId()).isNotEqualTo(second.documentId());
        drainKnowledge();
        assertThat(knowledge.searchScoped("scopecanary", system, "2.0", 5, true).passages()).isNotEmpty().allMatch(value -> value.documentId().equals(first.documentId()));
        assertThat(knowledge.searchScoped("scopecanary", otherSystem, "2.0", 5, true).passages()).isNotEmpty().allMatch(value -> value.documentId().equals(second.documentId()));
        assertThat(knowledge.searchScoped("scopecanary", system, "3.0", 5, true).passages()).isEmpty();
        assertThat(database.queryForObject("SELECT COUNT(*) FROM documents WHERE checksum=(SELECT checksum FROM documents WHERE id=?) AND system_id IN (?,?)", Integer.class, first.documentId(), system, otherSystem)).isEqualTo(2);
        scopedOnnx(system, otherSystem, first.documentId(), second.documentId());
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("合成数据");sheet.createRow(0).createCell(0).setCellValue("金额");sheet.createRow(1).createCell(0).setCellValue(12.5);
            workbook.write(output);
            KnowledgeViews.Upload table = library.submitScoped(system, "scope.xlsx", "系统表格", "2.0", null, null, UUID.randomUUID().toString(), "TEST", output.toByteArray(), ImportOptions.defaults());
            drainKnowledge();
            String dataset = excel.datasets(table.batchId()).getFirst().id();
            assertThat(excel.queryScoped(dataset, "SELECT COUNT(*) AS count FROM data", system, "2.0").rows()).containsExactly(List.of("1"));
            assertThatThrownBy(() -> excel.queryScoped(dataset, "SELECT COUNT(*) FROM data", otherSystem, "2.0")).hasMessageContaining("DATASET_NOT_APPLICABLE");
        }
        browser("/api/memories", object("content", "项目通用约束", "confirmed", true, "projectId", project), 200);
        browser("/api/memories", object("content", "第一系统约束", "confirmed", true, "projectId", project, "systemId", system), 200);
        browser("/api/memories", object("content", "第一环境约束", "confirmed", true, "projectId", project, "systemId", system, "environmentId", environment), 200);
        JsonNode scoped = browser("/api/memories?projectId=" + project + "&systemId=" + system + "&environmentId=" + environment, null, 200);
        assertThat(scoped.size()).isEqualTo(3);
        JsonNode other = browser("/api/memories?projectId=" + project + "&systemId=" + otherSystem, null, 200);
        assertThat(other.size()).isEqualTo(1);
        library.delete(first.documentId());
        assertThat(knowledge.searchScoped("scopecanary", system, "2.0", 5, true).passages()).isEmpty();
        assertThat(knowledge.searchScoped("scopecanary", otherSystem, "2.0", 5, true).passages()).isNotEmpty();
    }
    /** 显式提供已核实的配套权重时，用实际ONNX和正式发布过滤验证同版本两系统，未配置不伪造通过。 */
    private void scopedOnnx(String system, String otherSystem, String firstDocument, String secondDocument) throws Exception {
        if (System.getProperty("rerank.test.model") == null) {
            System.out.println("ENTERPRISE_ONNX_SCOPE_NOT_RUN");
            return;
        }
        MockEnvironment environment = new MockEnvironment().withProperty("supportops.reranking.enabled", "true")
                .withProperty("supportops.reranking.model-path", System.getProperty("rerank.test.model"))
                .withProperty("supportops.reranking.tokenizer-path", System.getProperty("rerank.test.tokenizer"))
                .withProperty("supportops.reranking.timeout-ms", "30000");
        try (OnnxPassageReranker reranker = new OnnxPassageReranker(environment)) {
            KnowledgeService scoped = new KnowledgeServiceImpl(library, context.getBean(KnowledgeCatalogMapper.class),
                    context.getBean(DocumentReleaseMapper.class), context.getBean(IndexTaskMapper.class),
                    context.getBean(EmbeddingClient.class), context.getBean(ElasticKnowledgeIndex.class), reranker,
                    context.getBean(DataPolicyService.class));
            KnowledgeSearchResult first = scoped.searchScoped("支付配置 scopecanary", system, "2.0", 5, true);
            KnowledgeSearchResult second = scoped.searchScoped("支付配置 scopecanary", otherSystem, "2.0", 5, true);
            assertThat(first.ranking().method()).isEqualTo("ONNX");
            assertThat(second.ranking().method()).isEqualTo("ONNX");
            assertThat(first.passages()).isNotEmpty().allMatch(value -> value.documentId().equals(firstDocument) && value.rerankScore() != null && Double.isFinite(value.rerankScore()));
            assertThat(second.passages()).isNotEmpty().allMatch(value -> value.documentId().equals(secondDocument) && value.rerankScore() != null && Double.isFinite(value.rerankScore()));
            assertThat(scoped.searchScoped("scopecanary", system, "3.0", 5, true).passages()).isEmpty();
            System.out.println("ENTERPRISE_ONNX_SCOPE=" + JSON.writeValueAsString(Map.of("status", "PASSED", "modelId", first.ranking().modelId(),
                    "systemsSeparated", 2, "wrongVersionEmpty", true, "storage", "REAL_MYSQL_MINIO_ES")));
        }
    }
    /** 测试主动执行本隔离库任务，失败原样记录并重新抛出，不启动后台消费者。 */
    private void drainKnowledge() {
        IndexTaskEntity task;
        while ((task = knowledgeTasks.claim()) != null) {
            try { library.execute(task); knowledgeTasks.finish(task, null); }
            catch (RuntimeException exception) { knowledgeTasks.finish(task, exception); throw exception; }
        }
    }
    /** 创建资源，标准请求全部通过真实账号 Cookie 和过滤器。 */
    private JsonNode resource(String kind, String parent, String code, String name, String product, String version) throws Exception {
        return browser("/api/enterprise/" + kind, object("requestId", UUID.randomUUID().toString(), "parentId", parent,
                "code", code, "name", name, "productKey", product, "productVersion", version), 200);
    }
    /** 第二个接入只增加配置，不修改诊断核心。 */
    private JsonNode connect(JsonNode environment, Sample source) throws Exception {
        ObjectNode config = object("baseUrl", "http://127.0.0.1:" + source.port(), "allowLocalHttp", true);
        config.set("allowedAddresses", JSON.valueToTree(List.of("127.0.0.1")));
        config.set("capabilities", JSON.valueToTree(List.of("APP_INFO", "EFFECTIVE_CONFIG", "RECENT_LOGS", "DOWNSTREAM_HEALTH")));
        config.set("modelData", JSON.valueToTree(List.of("APP_INFO", "DOWNSTREAM_HEALTH")));
        ObjectNode input = object("requestId", UUID.randomUUID().toString(), "environmentId", environment.path("id").asText(),
                "type", "STANDARD_REST", "bearerSecret", source.token()); input.set("config", config);
        JsonNode connector = browser("/api/enterprise/connectors", input, 200);
        JsonNode tested = browser("/api/enterprise/connectors/test", object("requestId", UUID.randomUUID().toString(), "revisionId", connector.path("id").asText()), 200);
        assertThat(tested.path("testStatus").asText()).isEqualTo("PASSED");
        browser("/api/enterprise/connectors/enable", object("requestId", UUID.randomUUID().toString(), "revisionId", connector.path("id").asText(), "expectedVersion", 1), 200);
        return connector;
    }
    /** 创建只授权指定环境的独立调用应用。 */
    private JsonNode application(String project, String environment, String code) throws Exception {
        ObjectNode input = object("requestId", UUID.randomUUID().toString(), "projectId", project, "code", code, "name", code, "status", "ACTIVE");
        input.set("permissions", JSON.valueToTree(List.of("DIAGNOSE", "READ", "CANCEL")));
        input.set("environmentIds", JSON.valueToTree(List.of(environment)));
        return browser("/api/enterprise/applications", input, 200);
    }
    /** 等待持久化终态，不断言不确定的自然语言效果。 */
    private JsonNode waitFor(String runId, String token) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode value = machine("GET", "/open-api/v1/diagnoses/" + runId, token, null, 200);
            if (!List.of("QUEUED", "RUNNING").contains(value.path("status").asText())) { return value; }
            Thread.sleep(200);
        }
        throw new AssertionError("TASK_TERMINAL_TIMEOUT");
    }
    /** HTTP 响应失败只输出安全错误代码，不在断言中回显密钥。 */
    private JsonNode browser(String path, JsonNode body, int status) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(45));
        if (cookie != null) { builder.header("Cookie", cookie); }
        if (body != null) { builder.header("X-SupportOps-Request", "1").header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())); }
        HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        response.headers().firstValue("Set-Cookie").ifPresent(value -> cookie = value.split(";", 2)[0]);
        JsonNode parsed = JSON.readTree(response.body());
        assertThat(response.statusCode()).withFailMessage("%s HTTP %s expected %s code=%s", path, response.statusCode(), status, parsed.path("error").asText()).isEqualTo(status);
        return parsed;
    }
    /** 机器 API 不携带 Cookie 或人员认证头。 */
    private JsonNode machine(String method, String path, String token, JsonNode body, int status) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(45));
        if (token != null) { builder.header("Authorization", "Bearer " + token); }
        if (body != null) { builder.header("Content-Type", "application/json"); }
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body.toString()));
        HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode parsed = JSON.readTree(response.body());
        assertThat(response.statusCode()).withFailMessage("%s HTTP %s expected %s code=%s", path, response.statusCode(), status, parsed.path("error").asText()).isEqualTo(status);
        return parsed;
    }
    /** 构建合成协议 JSON；动态结构只用于 HTTP 夹具，不承担业务模型。 */
    private ObjectNode object(Object... pairs) {
        ObjectNode result = JSON.createObjectNode();
        for (int index = 0; index < pairs.length; index += 2) { result.set((String) pairs[index], JSON.valueToTree(pairs[index + 1])); }
        return result;
    }
    /** 记录独立源进程和合成运行秘密，禁止自动生成的文本回显秘密。 */
    private record Sample(Process process, int port, String token) {
        /** 调试文本不回显秘密。 */
        @Override public String toString() { return "Sample[redacted]"; }
    }
    /** 通过 Java 源文件模式启动独立源，秘密通过受控文件传递。 */
    private static Sample sample(String system) throws Exception {
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy());
        Path file = DIRECTORY.resolve(system + ".secret"); Files.writeString(file, secret);
        Path java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        ProcessBuilder builder = new ProcessBuilder(java.toString(), Path.of("examples/integration/java-diagnostic-source/src/DiagnosticSource.java").toAbsolutePath().toString());
        builder.environment().clear();
        if (System.getenv("SystemRoot") != null) { builder.environment().put("SystemRoot", System.getenv("SystemRoot")); }
        builder.environment().putAll(Map.of("SOURCE_SYSTEM", system, "SOURCE_ENVIRONMENT", "test", "SOURCE_PRODUCT", "example-erp",
                "SOURCE_VERSION", "2.0", "SOURCE_SECRET_FILE", file.toString(), "SOURCE_PORT", "0"));
        builder.redirectError(DIRECTORY.resolve(system + ".stderr").toFile()); Process process = builder.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            if (line == null) { throw new IllegalStateException("INDEPENDENT_SOURCE_START_FAILED"); }
            return new Sample(process, JSON.readTree(line).path("port").asInt(), secret);
        }
    }
    /** 为测试生成256位秘密，不读取既有配置凭据。 */
    private static byte[] entropy() { byte[] value = new byte[32]; new SecureRandom().nextBytes(value); return value; }
    /** 结束自己的进程及随机存储，保留测试日志。 */
    @AfterAll static void cleanup() throws Exception {
        FIRST.process().destroy(); SECOND.process().destroy(); MODEL.stop(0); RESOURCES.close();
    }
}
