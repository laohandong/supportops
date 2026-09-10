package io.supportops.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.minio.BucketExistsArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.RemoveBucketArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.messages.Item;

import org.flywaydb.core.Flyway;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

/** 每组验证创建独立 MySQL 数据域与 ES/MinIO 命名空间，连接均由专用测试环境提供。 */
public final class KnowledgeTestResources implements AutoCloseable {
    public final String database =
            "so_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    public final String excelSchema =
            "so_xl_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    public final String prefix = "so-it-" + UUID.randomUUID().toString().substring(0, 8);
    public final String mysql =
            System.getProperty("supportops.test.mysql", "jdbc:mysql://127.0.0.1:23306/");
    public final String admin = System.getProperty("supportops.test.mysql.admin", "root");
    public final String adminPassword =
            System.getProperty("supportops.test.mysql.password", "synthetic-knowledge-root");
    public final String es = System.getProperty("supportops.test.es", "http://127.0.0.1:29200");
    public final String minio =
            System.getProperty("supportops.test.minio", "http://127.0.0.1:29000");
    public final String user = "supportops";
    public final String password = "synthetic-knowledge-app";
    public final String queryUser = "supportops_reader";
    public final String queryPassword = "synthetic-knowledge-reader";
    private boolean closed;

    /** 只对随机测试名称建库授权，不接触应用默认数据库。 */
    public KnowledgeTestResources() {
        if (!mysql.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/")) {
            throw new IllegalArgumentException("TEST_DATABASE_MUST_USE_EXPLICIT_LOOPBACK_PORT");
        }
        try (Connection connection = DriverManager.getConnection(mysql, admin, adminPassword);
                Statement sql = connection.createStatement()) {
            for (String schema : List.of(database, excelSchema)) {
                sql.execute(
                        "CREATE DATABASE `"
                                + schema
                                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
                sql.execute("GRANT ALL ON `" + schema + "`.* TO 'supportops'@'%'");
            }
            sql.execute("GRANT SELECT ON `" + excelSchema + "`.* TO 'supportops_reader'@'%'");
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Start the isolated knowledge test infrastructure before Maven verify",
                    exception);
        }
    }

    /** JDBC URL 固定 UTC 并保留 Unicode。 */
    public String url() {
        return mysql + database + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC";
    }

    /** 直接 Mapper 测试也使用与应用相同的 Flyway 迁移。 */
    public void migrate() {
        Flyway.configure().dataSource(url(), user, password).load().migrate();
    }

    /** Spring 集成测试隔离原件、索引、配置文件及数据库身份。 */
    public void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", this::url);
        properties.add("spring.datasource.username", () -> user);
        properties.add("spring.datasource.password", () -> password);
        properties.add("supportops.knowledge.excel.schema", () -> excelSchema);
        properties.add("supportops.knowledge.excel.query-user", () -> queryUser);
        properties.add("supportops.knowledge.excel.query-password", () -> queryPassword);
        properties.add("supportops.knowledge.elastic.endpoint", () -> es);
        properties.add("supportops.knowledge.elastic.prefix", () -> prefix);
        properties.add("supportops.knowledge.storage.endpoint", () -> minio);
        properties.add("supportops.knowledge.storage.access-key", () -> "synthetic-local");
        properties.add(
                "supportops.knowledge.storage.secret-key", () -> "synthetic-knowledge-minio");
        properties.add("supportops.knowledge.storage.bucket", () -> prefix);
        properties.add("supportops.knowledge.jobs.enabled", () -> false);
    }

    /** 关闭仅删除本实例随机创建的数据库和索引；错误必须可见。 */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try (Connection connection = DriverManager.getConnection(mysql, admin, adminPassword);
                Statement sql = connection.createStatement()) {
            for (String schema : List.of(excelSchema, database)) {
                if (!schema.matches("so_(it|xl)_[a-f0-9]{16}")) {
                    throw new IllegalStateException("INVALID_TEST_CLEANUP_TARGET");
                }
                sql.execute("DROP DATABASE `" + schema + "`");
            }
            HttpClient http = HttpClient.newHttpClient();
            HttpResponse<String> indices =
                    http.send(
                            HttpRequest.newBuilder(
                                            URI.create(
                                                    es
                                                            + "/_cat/indices/"
                                                            + prefix
                                                            + "-*?format=json&h=index"))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            if (indices.statusCode() != 200) {
                throw new IllegalStateException("TEST_ES_CLEANUP_FAILED");
            }
            for (JsonNode index : new ObjectMapper().readTree(indices.body())) {
                String name = index.path("index").asText();
                if (!name.startsWith(prefix + "-")) {
                    throw new IllegalStateException("INVALID_TEST_CLEANUP_INDEX");
                }
                HttpResponse<String> result =
                        http.send(
                                HttpRequest.newBuilder(URI.create(es + "/" + name))
                                        .DELETE()
                                        .build(),
                                HttpResponse.BodyHandlers.ofString());
                if (result.statusCode() != 200) {
                    throw new IllegalStateException("TEST_ES_CLEANUP_FAILED");
                }
            }
            MinioClient storage =
                    MinioClient.builder()
                            .endpoint(minio)
                            .credentials("synthetic-local", "synthetic-knowledge-minio")
                            .build();
            if (storage.bucketExists(BucketExistsArgs.builder().bucket(prefix).build())) {
                for (Result<Item> item :
                        storage.listObjects(
                                ListObjectsArgs.builder().bucket(prefix).recursive(true).build())) {
                    storage.removeObject(
                            RemoveObjectArgs.builder()
                                    .bucket(prefix)
                                    .object(item.get().objectName())
                                    .build());
                }
                storage.removeBucket(RemoveBucketArgs.builder().bucket(prefix).build());
            }
        } catch (Exception exception) {
            throw new IllegalStateException("TEST_RESOURCE_CLEANUP_FAILED", exception);
        }
    }
}
