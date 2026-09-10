package io.supportops.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.supportops.config.vo.AvailableModels;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.TreeSet;

/** 只读取已校验端点的模型目录，不跟随重定向，不返回服务商错误正文。 */
@Component
public class ModelCatalogClient {
    private final ObjectMapper json;

    /** 注入 JSON 解析器，目录请求不产生诊断任务。 */
    public ModelCatalogClient(ObjectMapper json) {
        this.json = json;
    }

    /** 以草稿凭据获取目录；超时、鉴权和协议失败映射为固定公开错误码。 */
    public AvailableModels fetch(ProviderRegistry.Endpoint endpoint) {
        endpoint.requireConfigured();
        try (HttpClient client =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build()) {
            HttpRequest request =
                    HttpRequest.newBuilder(URI.create(endpoint.baseUrl() + "/models"))
                            .timeout(Duration.ofSeconds(15))
                            .header("Authorization", "Bearer " + endpoint.apiKey())
                            .header("Accept", "application/json")
                            .GET()
                            .build();
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status != 200) {
                String code =
                        switch (status) {
                            case 401, 403 -> "MODEL_CATALOG_UNAUTHORIZED";
                            case 404, 405 -> "MODEL_CATALOG_UNSUPPORTED";
                            case 429 -> "MODEL_CATALOG_RATE_LIMITED";
                            default -> "MODEL_CATALOG_FAILED";
                        };
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, code);
            }
            if (response.body().length() > 2_000_000) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY, "MODEL_CATALOG_INVALID_RESPONSE");
            }
            JsonNode data = json.readTree(response.body()).path("data");
            if (!data.isArray() || data.size() > 10000) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY, "MODEL_CATALOG_INVALID_RESPONSE");
            }
            TreeSet<String> ids = new TreeSet<>();
            for (JsonNode item : data) {
                JsonNode id = item.path("id");
                if (!id.isTextual()
                        || id.asText().isBlank()
                        || id.asText().length() > 200
                        || id.asText().chars().anyMatch(Character::isISOControl)) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_GATEWAY, "MODEL_CATALOG_INVALID_RESPONSE");
                }
                ids.add(id.asText());
            }
            return new AvailableModels(List.copyOf(ids));
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (HttpTimeoutException exception) {
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "MODEL_CATALOG_TIMEOUT");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "MODEL_CATALOG_FAILED");
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "MODEL_CATALOG_FAILED");
        }
    }
}
