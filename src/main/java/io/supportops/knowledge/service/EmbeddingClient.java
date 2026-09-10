package io.supportops.knowledge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.supportops.config.ProviderRegistry;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/** 调用所选兼容接口计算真实向量，不产生合成向量或静默掩盖调用失败。 */
@Component
public class EmbeddingClient {
    private final ProviderRegistry providers;
    private final ObjectMapper json;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** 注入独立向量路由解析与 JSON 编解码组件。 */
    public EmbeddingClient(ProviderRegistry providers, ObjectMapper json) {
        this.providers = providers;
        this.json = json;
    }

    /** 检查本地配置是否满足执行要求，不发起远程模型请求。 */
    public boolean configured() {
        return providers.embedding().configured();
    }

    /** 返回当前向量模型名称。 */
    public String model() {
        return providers.embedding().model();
    }

    /** 取得本次索引或检索应固定使用的端点快照。 */
    public ProviderRegistry.Endpoint snapshot() {
        return providers.embedding();
    }

    /** 根据基础地址与模型构建索引标识，不把密钥写入指纹。 */
    public String fingerprint() {
        return fingerprint(snapshot());
    }

    /** 根据基础地址与模型构建索引标识，不把密钥写入指纹。 */
    public String fingerprint(ProviderRegistry.Endpoint endpoint) {
        return endpoint.configured() ? endpoint.baseUrl() + "|" + endpoint.model() : "";
    }

    /** 使用已选端点生成并校验数值向量；无效响应与中断均明确报告。 */
    public double[] embed(String text) {
        return embed(snapshot(), text);
    }

    /** 使用已选端点生成并校验数值向量；无效响应与中断均明确报告。 */
    public double[] embed(ProviderRegistry.Endpoint endpoint, String text) {
        endpoint.requireConfigured();
        try {
            URI uri = URI.create(endpoint.baseUrl() + "/embeddings");
            if (!"https".equals(uri.getScheme())
                    && !Set.of("127.0.0.1", "localhost", "[::1]").contains(uri.getHost())) {
                throw new IllegalStateException("HTTPS_REQUIRED");
            }
            HttpRequest req =
                    HttpRequest.newBuilder(uri)
                            .timeout(Duration.ofSeconds(30))
                            .header("Authorization", "Bearer " + endpoint.apiKey())
                            .header("Content-Type", "application/json")
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            json.writeValueAsString(
                                                    Map.of(
                                                            "model",
                                                            endpoint.model(),
                                                            "input",
                                                            text))))
                            .build();
            HttpResponse<String> response = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("EMBEDDING_HTTP_" + response.statusCode());
            }
            JsonNode vector = json.readTree(response.body()).path("data").path(0).path("embedding");
            if (!vector.isArray() || vector.isEmpty()) {
                throw new IllegalStateException("INVALID_EMBEDDING_RESPONSE");
            }
            double[] result = new double[vector.size()];
            double magnitude = 0;
            for (int i = 0; i < result.length; i++) {
                if (!vector.get(i).isNumber()) {
                    throw new IllegalStateException("INVALID_EMBEDDING_RESPONSE");
                }
                result[i] = vector.get(i).asDouble();
                if (!Double.isFinite(result[i])) {
                    throw new IllegalStateException("INVALID_EMBEDDING_RESPONSE");
                }
                magnitude += result[i] * result[i];
            }
            if (magnitude == 0) {
                throw new IllegalStateException("ZERO_EMBEDDING");
            }
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("EMBEDDING_INTERRUPTED");
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("EMBEDDING_UNAVAILABLE");
        }
    }
}
