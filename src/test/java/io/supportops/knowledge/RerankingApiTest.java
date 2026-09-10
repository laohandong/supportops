package io.supportops.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.supportops.api.ApiErrors;
import io.supportops.knowledge.controller.KnowledgeController;
import io.supportops.knowledge.service.DocumentLibraryService;
import io.supportops.knowledge.service.KnowledgeService;
import io.supportops.knowledge.service.support.RerankingException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** 独立回环 HTTP 验证真实 Controller、错误映射及生成的 OpenAPI；业务存储在此使用明确替身。 */
@SpringBootTest(classes = RerankingApiTest.ApiConfiguration.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.location=optional:classpath:/rerank-api-test.yml", "server.address=127.0.0.1",
                "springdoc.api-docs.version=OPENAPI_3_0",
                "spring.profiles.active=rerank-api-test"})
class RerankingApiTest {
    @LocalServerPort
    private int port;
    @MockitoBean
    private KnowledgeService knowledge;
    @MockitoBean
    private DocumentLibraryService library;

    /** 只装配接口文档所需组件，不连接或修改工作台数据库与配置。 */
    @Configuration(proxyBeanMethods = false)
    @Profile("rerank-api-test")
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
    @Import({KnowledgeController.class, ApiErrors.class})
    static class ApiConfiguration {}

    /** 实际生成的 OpenAPI 必须包含新增嵌套引用与分数字段。 */
    @Test
    void generatedOpenApiIncludesRankingEvidence() throws Exception {
        HttpResponse<String> response = get("/v3/api-docs");
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode spec = new ObjectMapper().readTree(response.body());
        assertThat(spec.at("/components/schemas/KnowledgeSearchResult/properties/ranking/allOf/0/$ref").asText())
                .isEqualTo("#/components/schemas/RankingInfo");
        assertThat(spec.at("/components/schemas/KnowledgeSearchResult/properties/ranking/description").asText())
                .contains("本次实际排序证据", "旧事件");
        assertThat(spec.at("/components/schemas/RankingInfo/properties/method/enum").toString())
                .contains("ONNX", "RRF", "EMPTY");
        assertThat(spec.at("/components/schemas/KnowledgePassage/properties/rerankScore/description").asText())
                .contains("logits", "未重排");
    }

    /** HTTP 调用明确返回安全错误码，不输出本地库异常或返回成功候选。 */
    @Test
    void inferenceFailureReturnsServiceUnavailable() throws Exception {
        when(knowledge.search(anyString(), anyString(), anyInt(), anyBoolean()))
                .thenThrow(new RerankingException("RERANK_TIMED_OUT"));
        HttpResponse<String> response = get("/api/documents/search?query=configuration&version=2.0");
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("RERANK_TIMED_OUT").doesNotContain("passages", "model_quantized.onnx");
    }

    /** 使用随机回环端口发起真实 HTTP，不连接其他服务。 */
    private HttpResponse<String> get(String path) throws Exception {
        try (HttpClient http = HttpClient.newHttpClient()) {
            return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }
}
