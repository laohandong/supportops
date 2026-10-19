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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
        if (!mysql.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/") || mysql.equals("jdbc:mysql://127.0.0.1:13306/")) {
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
                                + excel
                                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
                // 标记放在 Excel 库，保持应用库为空，让 Flyway 正常初始化；DDL 失败保留归属。
                statement.execute("CREATE TABLE `" + excel + "`._evaluation_workspace_owner (workspace_key VARCHAR(24) PRIMARY KEY, owns_main BOOLEAN NOT NULL DEFAULT FALSE)");
                try (PreparedStatement ownership = connection.prepareStatement(
                        "INSERT INTO `" + excel + "`._evaluation_workspace_owner (workspace_key) VALUES (?)")) {
                    ownership.setString(1, args[1]);
                    ownership.executeUpdate();
                }
                try {
                    statement.execute(
                            "CREATE DATABASE `"
                                    + database
                                    + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
                    statement.execute("UPDATE `" + excel + "`._evaluation_workspace_owner SET owns_main=TRUE");
                    statement.execute(
                            "GRANT ALL PRIVILEGES ON `" + database + "`.* TO 'supportops'@'%'");
                    statement.execute(
                            "GRANT ALL PRIVILEGES ON `" + excel + "`.* TO 'supportops'@'%'");
                    statement.execute(
                            "GRANT SELECT ON `" + excel + "`.* TO 'supportops_reader'@'%'");
                } catch (Exception exception) {
                    // 保留所有权标记，调用方按提前登记清单回收已经创建的资源。
                    throw new IllegalStateException("EVALUATION_PREPARATION_FAILED");
                }
            } else {
                boolean owned = databaseExists(connection, excel);
                boolean ownsMain = false;
                if (owned) {
                    try (Statement ownership = connection.createStatement();
                            ResultSet records = ownership.executeQuery("SELECT workspace_key, owns_main FROM `" + excel + "`._evaluation_workspace_owner")) {
                        if (!records.next() || !args[1].equals(records.getString(1))) {
                            throw new IllegalStateException("EVALUATION_OWNERSHIP_UNCONFIRMED");
                        }
                        ownsMain = records.getBoolean(2);
                        if (records.next()) {
                            throw new IllegalStateException("EVALUATION_OWNERSHIP_UNCONFIRMED");
                        }
                    }
                } else if (databaseExists(connection, database)) {
                    throw new IllegalStateException("EVALUATION_OWNERSHIP_UNCONFIRMED");
                }
                if (owned && !ownsMain && databaseExists(connection, database)) {
                    // CREATE DATABASE 与归属标记之间的未知结果不能推断拥有主库；保留标记等待核查。
                    throw new IllegalStateException("EVALUATION_OWNERSHIP_UNCONFIRMED");
                }
                cleanupStores(prefix, owned);
                if (owned) {
                    if (ownsMain) {
                        statement.execute("DROP DATABASE IF EXISTS `" + database + "`");
                    }
                    // 归属标记最后删除，存储清理失败时可以继续精确核对。
                    statement.execute("DROP DATABASE `" + excel + "`");
                }
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
    private static void cleanupStores(String prefix, boolean owned) throws Exception {
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
                if (!owned) {
                    throw new IllegalStateException("EVALUATION_OWNERSHIP_UNCONFIRMED");
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
                if (!owned) {
                    throw new IllegalStateException("EVALUATION_OWNERSHIP_UNCONFIRMED");
                }
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

    /** 存储管理工具只读核对精确库名，不枚举或清理其他数据库。 */
    private static boolean databaseExists(Connection connection, String database) throws Exception {
        try (PreparedStatement query = connection.prepareStatement("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?")) {
            query.setString(1, database);
            try (ResultSet result = query.executeQuery()) {
                result.next();
                return result.getInt(1) == 1;
            }
        }
    }

    /** 评测管理接口只允许无凭据的本机端点。 */
    private static String loopback(String value) {
        if (!value.matches("http://127\\.0\\.0\\.1:[0-9]+") || Set.of("http://127.0.0.1:19000", "http://127.0.0.1:19200").contains(value)) {
            throw new IllegalArgumentException("EVALUATION_REQUIRES_LOOPBACK_STORAGE");
        }
        return value;
    }

    /** 环境默认值仅对应仓库提供的合成实验环境。 */
    private static String value(String key, String fallback) {
        return System.getenv().getOrDefault(key, fallback);
    }
}
