package io.supportops.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.supportops.persistence.KnowledgeTestResources;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 随机 HTTP 端口和真实独立数据库验证管理员权限、DTO、禁用行为与生成 OpenAPI。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"spring.config.import=", "supportops.evaluation.enabled=false", "supportops.model.provider=disabled", "supportops.embedding.provider=disabled"})
class EvaluationApiTest {
    private static final KnowledgeTestResources RESOURCES = new KnowledgeTestResources();
    private static final ObjectMapper JSON = new ObjectMapper();
    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;

    /** 配置文件和数据域由测试创建，避免读取工作台设置。 */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        RESOURCES.properties(properties);
        properties.add("supportops.settings.file", () -> Path.of(".cache/evaluation-api", RESOURCES.database, "settings.json").toAbsolutePath().toString());
    }

    /** 只清理该测试拥有的随机资源。 */
    @AfterAll
    static void close() {
        RESOURCES.close();
    }

    /** 即使网页隐藏菜单，后端仍独立拒绝未登录、普通用户与缺少请求头的写操作。 */
    @Test
    void retiredEvaluationEndpointsRemainAuthenticatedAndNeverWrite() throws Exception {
        assertThat(request("GET", "/api/evaluations/experiments", null, null, true).statusCode()).isEqualTo(401);
        HttpResponse<String> setup = request("POST", "/api/auth/setup", null, JSON.writeValueAsString(Map.of("username", "ec_admin", "password", "synthetic-evaluation-api-password")), true);
        assertThat(setup.statusCode()).isEqualTo(200);
        String cookie = setup.headers().firstValue("set-cookie").orElseThrow().split(";")[0];
        assertThat(request("GET", "/api/evaluations/experiments", cookie, null, true).statusCode()).isEqualTo(410);
        HttpResponse<String> gone = request("POST", "/api/evaluations/experiments", cookie, "{}", true);
        assertThat(gone.statusCode()).isEqualTo(410);
        assertThat(gone.body()).contains("EVALUATION_MOVED");
        assertThat(gone.headers().firstValue("Location")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM evaluation_experiments", Integer.class)).isZero();
        assertThat(request("POST", "/api/evaluations/experiments", cookie, "{}", false).statusCode()).isEqualTo(403);
        assertThat(request("POST", "/api/users", cookie, "{\"username\":\"ec_reader\",\"password\":\"synthetic-evaluation-reader-password\",\"role\":\"USER\"}", true).statusCode()).isEqualTo(200);
        String reader = request("POST", "/api/auth/login", null, "{\"username\":\"ec_reader\",\"password\":\"synthetic-evaluation-reader-password\"}", true).headers().firstValue("set-cookie").orElseThrow().split(";")[0];
        assertThat(request("GET", "/api/evaluations/experiments", reader, null, true).statusCode()).isEqualTo(403);
        JsonNode openapi = JSON.readTree(request("GET", "/v3/api-docs", cookie, null, true).body());
        assertThat(openapi.path("paths").has("/api/runs/{runId}/evaluation-exports")).isTrue();
        assertThat(openapi.path("paths").has("/api/evaluation-exports/{id}")).isTrue();
        assertThat(openapi.path("paths").has("/api/evaluations/experiments")).isFalse();
        assertThat(openapi.path("components").path("schemas").has("EvaluationExportRequest")).isTrue();
        assertThat(openapi.toString()).doesNotContain("workspacePath", "privateEnvironment", "synthetic-evaluation-api-password");
    }

    /** Cookie 与自定义头由每次请求显式传入，避免测试客户端隐式管理员态。 */
    private HttpResponse<String> request(String method, String path, String cookie, String body, boolean header) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (cookie != null) {
            request.header("Cookie", cookie);
        }
        if (header) {
            request.header("X-SupportOps-Request", "1");
        }
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
