package io.supportops.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.minio.BucketExistsArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.RemoveBucketArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.messages.Item;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/** 评测命令行专用资源工具，不注册为业务服务；只管理随机命名的回环实验资源。 */
public final class IsolatedKnowledgeWorkspace {
    /** 工具不可实例化。 */
    private IsolatedKnowledgeWorkspace() {}

    /** 创建或回收脚本生成的隔离资源，凭据只通过环境传入，不输出到日志。 */
    public static void main(String[] args) throws Exception {
        if (args.length != 2
                || !args[1].matches("[0-9a-f]{24}")
                || !Set.of("prepare", "cleanup").contains(args[0])) {
            throw new IllegalArgumentException("INVALID_EVALUATION_WORKSPACE");
        }
        String mysql = value("SUPPORTOPS_TEST_MYSQL", "jdbc:mysql://127.0.0.1:23306/");
        if (!mysql.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/")) {
            throw new IllegalArgumentException("EVALUATION_REQUIRES_LOOPBACK_MYSQL");
        }
        String database = "so_eval_" + args[1];
        String excel = "so_xl_" + args[1];
        String prefix = "so-eval-" + args[1];
        try (Connection connection =
                        DriverManager.getConnection(
                                mysql,
                                "root",
                                value(
                                        "SUPPORTOPS_TEST_MYSQL_PASSWORD",
                                        "synthetic-knowledge-root"));
                Statement statement = connection.createStatement()) {
            if (args[0].equals("prepare")) {
                statement.execute(
                        "CREATE DATABASE `"
                                + database
                                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
                try {
                    statement.execute(
                            "CREATE DATABASE `"
                                    + excel
                                    + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
                    statement.execute(
                            "GRANT ALL PRIVILEGES ON `" + database + "`.* TO 'supportops'@'%'");
                    statement.execute(
                            "GRANT ALL PRIVILEGES ON `" + excel + "`.* TO 'supportops'@'%'");
                    statement.execute(
                            "GRANT SELECT ON `" + excel + "`.* TO 'supportops_reader'@'%'");
                } catch (Exception exception) {
                    // 主库由本调用独占创建；失败时保留可能存在的其他资源，避免误删同名库。
                    statement.execute("DROP DATABASE `" + database + "`");
                    throw new IllegalStateException("EVALUATION_PREPARATION_FAILED");
                }
            } else {
                cleanupStores(prefix);
                statement.execute("DROP DATABASE IF EXISTS `" + excel + "`");
                statement.execute("DROP DATABASE IF EXISTS `" + database + "`");
            }
        }
        System.out.println(
                "WORKSPACE:"
                        + new ObjectMapper()
                                .writeValueAsString(
                                        Map.of(
                                                "database",
                                                database,
                                                "excel",
                                                excel,
                                                "prefix",
                                                prefix,
                                                "mysql",
                                                mysql)));
    }

    /** 回收严格前缀匹配的索引及同名桶，禁止使用通配符删除索引。 */
    private static void cleanupStores(String prefix) throws Exception {
        String es = loopback(value("SUPPORTOPS_TEST_ES", "http://127.0.0.1:29200"));
        try (HttpClient http =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            HttpResponse<String> listing =
                    http.send(
                            HttpRequest.newBuilder(
                                            URI.create(
                                                    es
                                                            + "/_cat/indices/"
                                                            + prefix
                                                            + "-*?format=json&h=index"))
                                    .timeout(Duration.ofSeconds(10))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            if (listing.statusCode() != 200) {
                throw new IllegalStateException("EVALUATION_ES_CLEANUP_FAILED");
            }
            for (JsonNode entry : new ObjectMapper().readTree(listing.body())) {
                String index = entry.path("index").asText();
                if (!index.startsWith(prefix + "-") || !index.matches("[a-z0-9-]+")) {
                    throw new IllegalStateException("INVALID_EVALUATION_INDEX");
                }
                int status =
                        http.send(
                                        HttpRequest.newBuilder(URI.create(es + "/" + index))
                                                .timeout(Duration.ofSeconds(10))
                                                .DELETE()
                                                .build(),
                                        HttpResponse.BodyHandlers.discarding())
                                .statusCode();
                if (status != 200 && status != 404) {
                    throw new IllegalStateException("EVALUATION_ES_CLEANUP_FAILED");
                }
            }
        }
        // 命令行工具必须关闭客户端，避免请求线程使资源已清理的进程仍滞留。
        try (MinioClient minio =
                MinioClient.builder()
                        .endpoint(
                                loopback(value("SUPPORTOPS_TEST_MINIO", "http://127.0.0.1:29000")))
                        .credentials("synthetic-local", "synthetic-knowledge-minio")
                        .build()) {
            minio.setTimeout(5000, 10000, 10000);
            if (minio.bucketExists(BucketExistsArgs.builder().bucket(prefix).build())) {
                for (Result<Item> result :
                        minio.listObjects(
                                ListObjectsArgs.builder().bucket(prefix).recursive(true).build())) {
                    minio.removeObject(
                            RemoveObjectArgs.builder()
                                    .bucket(prefix)
                                    .object(result.get().objectName())
                                    .build());
                }
                minio.removeBucket(RemoveBucketArgs.builder().bucket(prefix).build());
            }
        }
    }

    /** 评测管理接口只允许无凭据的本机端点。 */
    private static String loopback(String value) {
        if (!value.matches("http://127\\.0\\.0\\.1:[0-9]+")) {
            throw new IllegalArgumentException("EVALUATION_REQUIRES_LOOPBACK_STORAGE");
        }
        return value;
    }

    /** 环境默认值仅对应仓库提供的合成实验环境。 */
    private static String value(String key, String fallback) {
        return System.getenv().getOrDefault(key, fallback);
    }
}
