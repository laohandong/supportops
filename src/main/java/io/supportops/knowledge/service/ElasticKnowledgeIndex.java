package io.supportops.knowledge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.supportops.knowledge.dto.ChunkSource;
import io.supportops.knowledge.service.support.KnowledgeValues;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** ES 8 的固定索引协议；动态 JSON 仅承载 ES Query DSL，不接受模型提供的 DSL。 */
@Component
public class ElasticKnowledgeIndex {
    private final ObjectMapper json;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String endpoint;
    private final String authorization;
    private final String prefix;

    /** 保存有来源和排名的 ES 命中，不返回向量载荷。 */
    public record Hit(ChunkSource source, double score) {}

    /** 校验固定地址与索引前缀；凭据只放在请求头。 */
    public ElasticKnowledgeIndex(
            ObjectMapper json,
            @Value("${supportops.knowledge.elastic.endpoint}") String endpoint,
            @Value("${supportops.knowledge.elastic.username:}") String user,
            @Value("${supportops.knowledge.elastic.password:}") String password,
            @Value("${supportops.knowledge.elastic.prefix:supportops}") String prefix) {
        this.json = json;
        this.endpoint = endpoint.replaceAll("/+$", "");
        URI uri = URI.create(this.endpoint);
        if (!prefix.matches("[a-z][a-z0-9_-]{0,40}")
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || (!"https".equals(uri.getScheme())
                        && !("http".equals(uri.getScheme())
                                && Set.of("localhost", "127.0.0.1", "[::1]")
                                        .contains(uri.getHost())))) {
            throw new IllegalArgumentException("INVALID_ES_CONFIGURATION");
        }
        this.prefix = prefix;
        this.authorization =
                user.isBlank()
                        ? ""
                        : "Basic "
                                + Base64.getEncoder()
                                        .encodeToString(
                                                (user + ":" + password)
                                                        .getBytes(StandardCharsets.UTF_8));
    }

    /** 文本索引不依赖向量模型。 */
    public String textIndex() {
        return prefix + "-text-v1";
    }

    /** 向量维度与模型指纹决定独立索引，不能混用向量空间。 */
    public String vectorIndex(String profile, int dimensions) {
        if (dimensions < 1 || dimensions > 4096) {
            throw new IllegalStateException("EMBEDDING_DIMENSION_UNSUPPORTED");
        }
        return prefix
                + "-vector-"
                + KnowledgeValues.hash(profile).substring(0, 20)
                + "-"
                + dimensions;
    }

    /** 显式建立字段映射，拒绝元数据自动扩张索引结构。 */
    public void ensure(String index, int dimensions) {
        if (request("HEAD", "/" + index, null, true) != null) {
            return;
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        // ID、产品版本和内容摘要用于精确过滤；这些字段不能经过正文分词。
        for (String field :
                List.of(
                        "id",
                        "documentId",
                        "versionId",
                        "batchId",
                        "fileId",
                        "filename",
                        "fileType",
                        "productVersion",
                        "contentHash",
                        "taskId",
                        "codes")) {
            fields.put(field, Map.of("type", "keyword"));
        }
        for (String field : List.of("content", "title", "heading", "headingPath")) {
            // 正文同时保留中文分词与 raw 子字段，兼顾自然语言和配置键等精确内容。
            fields.put(
                    field,
                    Map.of(
                            "type",
                            "text",
                            "analyzer",
                            "cjk",
                            "fields",
                            Map.of("raw", Map.of("type", "keyword", "ignore_above", 512))));
        }
        fields.put("location", Map.of("type", "keyword", "index", false));
        for (String field :
                List.of(
                        "revision",
                        "chunkIndex",
                        "pageStart",
                        "pageEnd",
                        "lineStart",
                        "lineEnd",
                        "charStart",
                        "charEnd",
                        "charCount")) {
            fields.put(field, Map.of("type", "integer"));
        }
        if (dimensions > 0) {
            fields.put(
                    "vector",
                    Map.of(
                            "type",
                            "dense_vector",
                            "dims",
                            dimensions,
                            "index",
                            true,
                            "similarity",
                            "cosine"));
        }
        try {
            request(
                    "PUT",
                    "/" + index,
                    Map.of(
                            "settings",
                            Map.of("number_of_shards", 1, "number_of_replicas", 0),
                            "mappings",
                            Map.of("dynamic", "strict", "properties", fields)),
                    false);
        } catch (IllegalStateException exception) {
            // 多个任务可能同时建索引；只有确认索引已存在，才能接受这一创建竞争。
            if (request("HEAD", "/" + index, null, true) == null) {
                throw exception;
            }
        }
    }

    /** 按批次写入并逐项检查，外部版本阻止过期执行者覆盖新尝试。 */
    public void putText(List<ChunkSource> sources, int epoch) {
        bulk(textIndex(), sources, null, null, epoch);
    }

    /** 写入一个已校验的向量，任务 ID 隔离并行重建。 */
    public void putVector(
            String index, String taskId, ChunkSource source, double[] vector, int epoch) {
        bulk(index, List.of(source), taskId, vector, epoch);
    }

    /** 根据稳定 ID 和摘要核对不确定写入，避免重复生成已有向量。 */
    public boolean hasVector(String index, String taskId, ChunkSource source) {
        JsonNode result =
                request("GET", "/" + index + "/_doc/" + taskId + "_" + source.id(), null, true);
        return result != null
                && result.path("_source").path("contentHash").asText().equals(source.contentHash());
    }

    /** 批量协议只序列化固定投影；向量单条写入也复用逐项检查。 */
    private void bulk(
            String index, List<ChunkSource> sources, String taskId, double[] vector, int epoch) {
        StringBuilder body = new StringBuilder();
        try {
            for (ChunkSource source : sources) {
                String id = taskId == null ? source.id() : taskId + "_" + source.id();
                // Bulk 是动作行与正文行交替的 NDJSON，最后一行也必须有换行。
                body.append(json.writeValueAsString(bulkAction(index, id, epoch))).append('\n');
                body.append(json.writeValueAsString(indexedSource(source, taskId, vector)))
                        .append('\n');
            }
            JsonNode response = request("POST", "/_bulk", body.toString(), false);
            // HTTP 成功不代表每条记录成功，部分写入必须交给任务补偿，不能直接发布。
            if (response.path("errors").asBoolean()) {
                throw new IllegalStateException("ES_BULK_PARTIAL_FAILURE");
            }
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("ES_SERIALIZATION_FAILED");
        }
    }

    /** 尝试次数作为外部版本：同次重试可重复写，旧执行者不能覆盖更新尝试的结果。 */
    private ObjectNode bulkAction(String index, String id, int epoch) {
        ObjectNode action = json.createObjectNode();
        ObjectNode metadata = action.putObject("index");
        metadata.put("_index", index);
        metadata.put("_id", id);
        metadata.put("version", epoch);
        metadata.put("version_type", "external_gte");
        return action;
    }

    /** 从固定来源投影组装正文，向量索引另外携带处理任务 ID 和数值向量。 */
    private ObjectNode indexedSource(ChunkSource source, String taskId, double[] vector) {
        ObjectNode value = json.valueToTree(source);
        value.set("codes", json.valueToTree(codes(source.title() + " " + source.content())));
        if (taskId != null) {
            value.put("taskId", taskId);
            value.set("vector", json.valueToTree(vector));
        }
        return value;
    }

    /** 刷新后确认完整数量，只有此后才可发布生效关系。 */
    public void verify(String index, String field, String value, int expected) {
        request("POST", "/" + index + "/_refresh", null, false);
        JsonNode count =
                request(
                        "POST",
                        "/" + index + "/_count",
                        Map.of("query", Map.of("term", Map.of(field, value))),
                        false);
        if (count.path("count").asInt(-1) != expected) {
            throw new IllegalStateException("ES_INDEX_INCOMPLETE");
        }
    }

    /** 关键词召回覆盖所有已发布批次，与是否向量化无关。 */
    public List<Hit> lexical(String query, String version, List<String> batchIds, int size) {
        if (batchIds.isEmpty()) {
            return List.of();
        }
        List<Object> should =
                List.of(
                        Map.of(
                                "multi_match",
                                Map.of(
                                        "query",
                                        query,
                                        "fields",
                                        List.of("title^3", "heading^2", "headingPath", "content"))),
                        Map.of("terms", Map.of("codes", codes(query))));
        return hits(
                request(
                        "POST",
                        "/" + textIndex() + "/_search",
                        Map.of(
                                "size",
                                size,
                                "query",
                                Map.of(
                                        "bool",
                                        Map.of(
                                                "filter",
                                                filters(version, batchIds),
                                                "should",
                                                should,
                                                "minimum_should_match",
                                                1))),
                        false));
    }

    /** 版本和已发布任务在 kNN 内部预过滤，不让旧版挤占召回窗口。 */
    public List<Hit> vector(
            String index,
            String version,
            List<String> batchIds,
            List<String> tasks,
            double[] vector,
            int size) {
        List<Object> filter = new ArrayList<>(filters(version, batchIds));
        filter.add(Map.of("terms", Map.of("taskId", tasks)));
        return hits(
                request(
                        "POST",
                        "/" + index + "/_search",
                        Map.of(
                                "size",
                                size,
                                "_source",
                                Map.of("excludes", List.of("vector")),
                                "knn",
                                Map.of(
                                        "field",
                                        "vector",
                                        "query_vector",
                                        vector,
                                        "k",
                                        size,
                                        "num_candidates",
                                        Math.max(100, size * 4),
                                        "filter",
                                        Map.of("bool", Map.of("filter", filter)))),
                        false));
    }

    /** 固定服务端筛选条件。 */
    private List<Object> filters(String version, List<String> batches) {
        return List.of(
                Map.of("terms", Map.of("batchId", batches)),
                Map.of(
                        "terms",
                        Map.of(
                                "productVersion",
                                version.equals("*") ? List.of("*") : List.of(version, "*"))));
    }

    /** 从固定来源投影还原可引用结果。 */
    private List<Hit> hits(JsonNode response) {
        List<Hit> result = new ArrayList<>();
        for (JsonNode hit : response.path("hits").path("hits")) {
            ObjectNode source = ((ObjectNode) hit.path("_source")).deepCopy();
            source.remove(List.of("vector", "codes", "taskId"));
            try {
                result.add(
                        new Hit(
                                json.treeToValue(source, ChunkSource.class),
                                hit.path("_score").asDouble()));
            } catch (Exception exception) {
                throw new IllegalStateException("ES_SOURCE_INVALID");
            }
        }
        return result;
    }

    /** 清理该文档全部文本与向量代，删除标记仍由 MySQL 保留。 */
    public void deleteDocument(String documentId) {
        request(
                "POST",
                "/"
                        + prefix
                        + "-*/_delete_by_query?conflicts=proceed&refresh=true&ignore_unavailable=true",
                Map.of("query", Map.of("term", Map.of("documentId", documentId))),
                true);
    }

    /** 提取配置键、路径和错误码，形成精确匹配辅助字段。 */
    private List<String> codes(String text) {
        List<String> values = new ArrayList<>();
        Matcher matcher = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:/-]*").matcher(text);
        while (matcher.find()) {
            values.add(matcher.group());
        }
        return values.stream().distinct().limit(1500).toList();
    }

    /** 限定内部 ES HTTP 协议，异常不包含原始响应或凭据。 */
    private JsonNode request(String method, String path, Object body, boolean missingAllowed) {
        try {
            String payload =
                    body == null
                            ? ""
                            : body instanceof String text ? text : json.writeValueAsString(body);
            HttpRequest.Builder request =
                    HttpRequest.newBuilder(URI.create(endpoint + path))
                            .timeout(Duration.ofSeconds(30))
                            .header(
                                    "Content-Type",
                                    path.equals("/_bulk")
                                            ? "application/x-ndjson"
                                            : "application/json");
            if (!authorization.isEmpty()) {
                request.header("Authorization", authorization);
            }
            HttpResponse<String> response =
                    http.send(
                            request.method(
                                            method,
                                            payload.isEmpty()
                                                    ? HttpRequest.BodyPublishers.noBody()
                                                    : HttpRequest.BodyPublishers.ofString(payload))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            if (missingAllowed && response.statusCode() == 404) {
                return null;
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("ES_HTTP_" + response.statusCode());
            }
            return response.body().isBlank()
                    ? json.createObjectNode()
                    : json.readTree(response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ES_INTERRUPTED");
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("ES_UNAVAILABLE");
        }
    }
}
