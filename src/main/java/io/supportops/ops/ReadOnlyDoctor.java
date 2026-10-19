package io.supportops.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import io.supportops.config.LocalModelSettings;
import io.supportops.config.ProviderRegistry;
import okhttp3.OkHttpClient;
import org.springframework.core.env.StandardEnvironment;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Properties;

/** 加载正式配置解析器后执行有限只读探针，绝不创建 Spring 容器或初始化资源。 */
public final class ReadOnlyDoctor {
    /** 检查结果固定为安全原因码，远端异常正文和认证信息不进入输出。 */
    public record Check(String id, String status, String code, String scope) {}

    /** 禁止实例化独立运维入口。 */
    private ReadOnlyDoctor() {}

    /** 复用 ConfigData 与 ProviderRegistry 的优先级，不调用模型或目录接口。 */
    public static void main(String[] args) throws Exception {
        List<Check> checks = new ArrayList<>();
        try {
            StandardEnvironment environment = RuntimeConfiguration.environment(args);
            ObjectMapper json = new ObjectMapper();
            ProviderRegistry providers = new ProviderRegistry(environment, new LocalModelSettings(environment, json));
            for (String role : List.of("model", "embedding")) {
                ProviderRegistry.Endpoint endpoint = role.equals("model") ? providers.dialogue() : providers.embedding();
                String source = environment.getProperty("supportops.settings.file", ".local/model-settings.json");
                // 来源判断读取角色键；不输出覆盖文件路径或密钥，解析行为仍由正式实现决定。
                Path settings = Path.of(source);
                boolean saved = Files.exists(settings) && json.readTree(Files.readString(settings)).path("roles").has(role);
                checks.add(new Check(role, "WARN", endpoint.configured() ? "MODEL_AUTH_NOT_VERIFIED" : endpoint.error(),
                        saved ? "UI_OVERRIDE" : "FILE_ENV"));
            }
            mysql(environment, checks);
            minio(environment, checks);
            elastic(environment, checks);
        } catch (Exception exception) {
            checks.add(new Check("configuration", "FAIL", "CONFIGURATION_INVALID", "LOCAL_CONFIG"));
        }
        System.out.println("OPS:" + new ObjectMapper().writeValueAsString(checks));
    }

    /** 独立 JDBC 只用于运维探针；启用只读连接且只执行 SELECT 1。 */
    private static void mysql(StandardEnvironment env, List<Check> checks) {
        Properties properties = new Properties();
        properties.setProperty("user", env.getProperty("spring.datasource.username", ""));
        properties.setProperty("password", env.getProperty("spring.datasource.password", ""));
        properties.setProperty("connectTimeout", "4000");
        properties.setProperty("socketTimeout", "4000");
        try (Connection connection = DriverManager.getConnection(env.getProperty("spring.datasource.url"), properties)) {
            connection.setReadOnly(true);
            try (Statement sql = connection.createStatement()) {
                sql.setQueryTimeout(4);
                try (ResultSet result = sql.executeQuery("SELECT 1")) {
                    if (!result.next() || result.getInt(1) != 1) {
                        throw new IllegalStateException();
                    }
                }
            }
            checks.add(new Check("mysql", "PASS", "READ_ONLY_QUERY_OK", "EXPLICIT_DATABASE"));
        } catch (Exception exception) {
            checks.add(new Check("mysql", "FAIL", "MYSQL_UNAVAILABLE", "EXPLICIT_DATABASE"));
        }
    }

    /** 使用原生只读 MinIO 客户端，绕开会建桶的业务适配器。 */
    private static void minio(StandardEnvironment env, List<Check> checks) {
        OkHttpClient http = new OkHttpClient.Builder().callTimeout(Duration.ofSeconds(5))
                .connectTimeout(Duration.ofSeconds(4)).readTimeout(Duration.ofSeconds(4))
                .retryOnConnectionFailure(false).build();
        try {
            MinioClient client = MinioClient.builder().endpoint(env.getProperty("supportops.knowledge.storage.endpoint"))
                    .credentials(env.getProperty("supportops.knowledge.storage.access-key"),
                            env.getProperty("supportops.knowledge.storage.secret-key"))
                    .httpClient(http).build();
            client.setTimeout(4000, 4000, 4000);
            boolean exists = client.bucketExists(BucketExistsArgs.builder()
                    .bucket(env.getProperty("supportops.knowledge.storage.bucket")).build());
            checks.add(new Check("minio", exists ? "PASS" : "WARN", exists ? "BUCKET_READABLE" : "BUCKET_NOT_CREATED", "EXPLICIT_BUCKET"));
        } catch (Exception exception) {
            checks.add(new Check("minio", "FAIL", "MINIO_UNAVAILABLE", "EXPLICIT_BUCKET"));
        } finally {
            // 独立命令必须关闭探针线程，不能等 HTTP 连接池空闲回收才退出。
            http.dispatcher().cancelAll();
            http.dispatcher().executorService().shutdown();
            http.connectionPool().evictAll();
        }
    }

    /** 只读取集群健康状态，禁止在客户端构造期间创建索引。 */
    private static void elastic(StandardEnvironment env, List<Check> checks) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(env.getProperty("supportops.knowledge.elastic.endpoint")
                    + "/_cluster/health?timeout=4s")).timeout(Duration.ofSeconds(5)).GET();
            String user = env.getProperty("supportops.knowledge.elastic.username", "");
            if (!user.isBlank()) {
                String credential = user + ":" + env.getProperty("supportops.knowledge.elastic.password", "");
                request.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(credential.getBytes(StandardCharsets.UTF_8)));
            }
            HttpResponse<String> response;
            try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build()) {
                response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            }
            boolean healthy = response.statusCode() == 200
                    && healthyCluster(new ObjectMapper().readTree(response.body()));
            checks.add(new Check("elasticsearch", healthy ? "PASS" : "FAIL", healthy ? "CLUSTER_READABLE" : "ES_UNAVAILABLE", "EXPLICIT_CLUSTER"));
        } catch (Exception exception) {
            checks.add(new Check("elasticsearch", "FAIL", "ES_UNAVAILABLE", "EXPLICIT_CLUSTER"));
        }
    }

    /** 只接受明确的 ES 健康结构；缺字段、未知状态和超时不能证明集群可读。 */
    static boolean healthyCluster(JsonNode health) {
        if (health == null || !health.isObject()) {
            return false;
        }
        JsonNode name = health.path("cluster_name");
        JsonNode status = health.path("status");
        JsonNode timedOut = health.path("timed_out");
        if (!name.isTextual() || name.asText().isBlank() || !status.isTextual()
                || !(status.asText().equals("green") || status.asText().equals("yellow"))
                || !timedOut.isBoolean() || timedOut.booleanValue()) {
            return false;
        }
        for (String field : List.of("number_of_nodes", "number_of_data_nodes", "active_primary_shards", "active_shards")) {
            JsonNode value = health.path(field);
            if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) {
                return false;
            }
        }
        return health.path("number_of_nodes").intValue() > 0
                && health.path("number_of_data_nodes").intValue() <= health.path("number_of_nodes").intValue()
                && health.path("active_primary_shards").intValue() <= health.path("active_shards").intValue();
    }
}
