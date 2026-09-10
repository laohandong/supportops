package io.supportops.migration;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.supportops.knowledge.dto.ImportOptions;
import io.supportops.knowledge.service.support.KnowledgeValues;

import org.flywaydb.core.Flyway;

import java.sql.Clob;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** 显式离线迁移工具；JDBC 仅用于旧库读取与一次性迁移，不进入应用业务服务。 */
public final class H2ToMysqlMigration {
    private static final List<String> LEGACY_TABLES =
            List.of("documents", "chunks", "memories", "runs", "run_events");
    private static final Map<String, List<String>> FIELDS =
            Map.of(
                    "documents",
                            List.of(
                                    "id",
                                    "title",
                                    "version",
                                    "filename",
                                    "checksum",
                                    "created_at",
                                    "embedding_key"),
                    "chunks", List.of("id", "document_id", "location", "content", "embedding"),
                    "memories", List.of("id", "content", "created_at"),
                    "runs",
                            List.of(
                                    "id",
                                    "session_id",
                                    "question",
                                    "status",
                                    "answer",
                                    "error_code",
                                    "created_at",
                                    "finished_at",
                                    "elapsed_ms",
                                    "input_tokens",
                                    "output_tokens"),
                    "run_events", List.of("id", "run_id", "kind", "content", "created_at"));

    /** 工具仅提供显式 main 入口。 */
    private H2ToMysqlMigration() {}

    /** 从环境变量读取连接参数，不把连接口令打印到命令行或报告。 */
    public static void main(String[] args) throws Exception {
        String source = required("SUPPORTOPS_MIGRATION_H2_URL");
        String target = required("SUPPORTOPS_DB_URL");
        if (!source.startsWith("jdbc:h2:file:") || !target.startsWith("jdbc:mysql:")) {
            throw new IllegalArgumentException("MIGRATION_REQUIRES_H2_FILE_AND_MYSQL");
        }
        String sourceUrl = source + ";IFEXISTS=TRUE;ACCESS_MODE_DATA=r";
        String user = required("SUPPORTOPS_DB_USER");
        String password = required("SUPPORTOPS_DB_PASSWORD");
        Flyway.configure().dataSource(target, user, password).load().migrate();
        try (Connection input = DriverManager.getConnection(sourceUrl, "sa", "");
                Connection output = DriverManager.getConnection(target, user, password)) {
            System.out.println(new ObjectMapper().writeValueAsString(migrate(input, output)));
        }
    }

    /** 目标业务表必须为空，全部事实和旧知识转换在同一 MySQL 事务中提交。 */
    public static Map<String, Long> migrate(Connection source, Connection target) throws Exception {
        boolean previousAutoCommit = target.getAutoCommit();
        target.setAutoCommit(false);
        try {
            try (Statement lock = target.createStatement();
                    ResultSet result =
                            lock.executeQuery("SELECT GET_LOCK('supportops_h2_migration',0)")) {
                if (!result.next() || result.getInt(1) != 1) {
                    throw new IllegalStateException("MIGRATION_ALREADY_RUNNING");
                }
            }
            for (String table : LEGACY_TABLES) {
                try (Statement check = target.createStatement();
                        ResultSet rows = check.executeQuery("SELECT COUNT(*) FROM " + table)) {
                    rows.next();
                    if (rows.getLong(1) != 0) {
                        throw new IllegalStateException("MIGRATION_TARGET_NOT_EMPTY");
                    }
                }
            }
            Map<String, Long> counts = new LinkedHashMap<>();
            for (String table : LEGACY_TABLES) {
                counts.put(table, copyTable(source, target, table));
            }
            convertKnowledge(target);
            target.commit();
            return Map.copyOf(counts);
        } catch (Exception exception) {
            target.rollback();
            throw exception;
        } finally {
            try (Statement unlock = target.createStatement()) {
                unlock.execute("SELECT RELEASE_LOCK('supportops_h2_migration')");
            }
            target.setAutoCommit(previousAutoCommit);
        }
    }

    /** 按固定字段复制旧记录，保留空值、长文本、时间及显式事件序号。 */
    private static long copyTable(Connection source, Connection target, String table)
            throws Exception {
        List<String> fields = FIELDS.get(table);
        String names = String.join(",", fields);
        long count = 0;
        try (Statement read = source.createStatement();
                ResultSet rows = read.executeQuery("SELECT " + names + " FROM " + table);
                PreparedStatement write =
                        target.prepareStatement(
                                "INSERT INTO "
                                        + table
                                        + " ("
                                        + names
                                        + ") VALUES ("
                                        + String.join(",", Collections.nCopies(fields.size(), "?"))
                                        + ")")) {
            while (rows.next()) {
                for (int index = 0; index < fields.size(); index++) {
                    Object value = rows.getObject(index + 1);
                    if (value instanceof Clob clob) {
                        value = clob.getSubString(1, Math.toIntExact(clob.length()));
                    }
                    write.setObject(index + 1, value);
                }
                write.executeUpdate();
                count++;
            }
        }
        return count;
    }

    /** 旧知识形成缺失原件的第一修订，保留片段 ID，排队重建 ES 关键词及向量。 */
    private static void convertKnowledge(Connection target) throws Exception {
        try (Statement read = target.createStatement();
                ResultSet documents =
                        read.executeQuery(
                                "SELECT id,title,version,filename,checksum,created_at FROM"
                                        + " documents")) {
            while (documents.next()) {
                String documentId = documents.getString("id");
                String file = UUID.randomUUID().toString(),
                        version = UUID.randomUUID().toString(),
                        batch = UUID.randomUUID().toString();
                String created = documents.getString("created_at");
                insert(
                        target,
                        "source_files",
                        row(
                                "id",
                                file,
                                "document_id",
                                documentId,
                                "object_key",
                                "originals/" + file,
                                "filename",
                                documents.getString("filename"),
                                "media_type",
                                "application/octet-stream",
                                "size_bytes",
                                0L,
                                "checksum",
                                documents.getString("checksum"),
                                "status",
                                "MISSING",
                                "created_at",
                                created));
                insert(
                        target,
                        "document_versions",
                        row(
                                "id",
                                version,
                                "document_id",
                                documentId,
                                "revision",
                                1,
                                "product_version",
                                documents.getString("version"),
                                "title",
                                documents.getString("title"),
                                "file_id",
                                file,
                                "file_type",
                                documents
                                                .getString("filename")
                                                .toLowerCase(Locale.ROOT)
                                                .endsWith(".pdf")
                                        ? "PDF"
                                        : "MD",
                                "note",
                                "从 H2 迁移；原件未留存，可按内容摘要补传",
                                "created_at",
                                created));
                int count = legacyChunkCount(target, documentId);
                // 旧表没有真实参数；保留与迁移列一致的占位快照，legacy-h2 对外显示为未知。
                // 不能把应用的新默认值写入旧批次，否则会声称旧分片按新规则生成。
                String options =
                        new ObjectMapper()
                                .writeValueAsString(
                                        new ImportOptions(750, 100, List.of(), 0, Map.of(), false));
                insert(
                        target,
                        "document_batches",
                        row(
                                "id",
                                batch,
                                "document_id",
                                documentId,
                                "version_id",
                                version,
                                "generation",
                                1,
                                "chunk_size",
                                750,
                                "overlap",
                                100,
                                "config_json",
                                options,
                                "config_hash",
                                KnowledgeValues.hash(options),
                                "parser_version",
                                "legacy-h2",
                                "status",
                                "READY",
                                "text_status",
                                "PENDING",
                                "vector_status",
                                "PENDING",
                                "chunk_count",
                                count,
                                "row_count",
                                0L,
                                "error_code",
                                "",
                                "created_at",
                                created));
                updateChunks(target, documentId, version, batch);
                insert(
                        target,
                        "document_uploads",
                        row(
                                "id",
                                UUID.randomUUID().toString(),
                                "request_key",
                                "migration:" + documentId,
                                "request_hash",
                                KnowledgeValues.hash(documentId),
                                "document_id",
                                documentId,
                                "version_id",
                                version,
                                "batch_id",
                                batch,
                                "file_id",
                                file,
                                "filename",
                                documents.getString("filename"),
                                "size_bytes",
                                0L,
                                "checksum",
                                documents.getString("checksum"),
                                "source",
                                "H2_MIGRATION",
                                "status",
                                "SUCCEEDED",
                                "error_code",
                                "ORIGINAL_FILE_MISSING",
                                "created_at",
                                created,
                                "finished_at",
                                KnowledgeValues.now()));
                insert(
                        target,
                        "knowledge_tasks",
                        row(
                                "id",
                                UUID.randomUUID().toString(),
                                "document_id",
                                documentId,
                                "version_id",
                                version,
                                "batch_id",
                                batch,
                                "kind",
                                "TEXT",
                                "status",
                                "PENDING",
                                "stage",
                                "QUEUED",
                                "profile_key",
                                "",
                                "index_name",
                                "",
                                "dimensions",
                                0,
                                "completed_chunks",
                                0,
                                "attempt_count",
                                0,
                                "budget_start",
                                0,
                                "next_retry_at",
                                0L,
                                "lease_owner",
                                "",
                                "lease_until",
                                0L,
                                "heartbeat_at",
                                0L,
                                "error_code",
                                "",
                                "created_at",
                                KnowledgeValues.now()));
                try (PreparedStatement update =
                        target.prepareStatement(
                                "UPDATE documents SET embedding_key='',canonical_key=? WHERE"
                                        + " id=?")) {
                    update.setString(
                            1,
                            KnowledgeValues.hash(
                                    documents.getString("checksum")
                                            + "|"
                                            + documents.getString("version")));
                    update.setString(2, documentId);
                    update.executeUpdate();
                }
            }
        }
    }

    /** 历史分片按原 ID 中的数值序号排序，位置不明时保留原位置说明而不虚构页码。 */
    private static void updateChunks(
            Connection target, String documentId, String version, String batch) throws Exception {
        List<LegacyChunk> chunks = new ArrayList<>();
        try (PreparedStatement read =
                target.prepareStatement("SELECT id,content FROM chunks WHERE document_id=?")) {
            read.setString(1, documentId);
            try (ResultSet rows = read.executeQuery()) {
                while (rows.next()) {
                    chunks.add(new LegacyChunk(rows.getString(1), rows.getString(2)));
                }
            }
        }
        chunks.sort(
                Comparator.comparingInt(H2ToMysqlMigration::legacyIndex)
                        .thenComparing(LegacyChunk::id));
        try (PreparedStatement update =
                target.prepareStatement(
                        "UPDATE chunks SET"
                            + " version_id=?,batch_id=?,chunk_index=?,content_hash=?,char_count=?,embedding=NULL"
                            + " WHERE id=?")) {
            for (int index = 0; index < chunks.size(); index++) {
                LegacyChunk chunk = chunks.get(index);
                update.setString(1, version);
                update.setString(2, batch);
                update.setInt(3, index + 1);
                update.setString(4, KnowledgeValues.hash(chunk.content()));
                update.setInt(5, chunk.content().codePointCount(0, chunk.content().length()));
                update.setString(6, chunk.id());
                update.executeUpdate();
            }
        }
    }

    /** 旧片段只承载迁移所需的 ID 与正文。 */
    private record LegacyChunk(String id, String content) {}

    /** 不规范的旧 ID 排在有明确序号的片段之后。 */
    private static int legacyIndex(LegacyChunk chunk) {
        try {
            return Integer.parseInt(chunk.id().substring(chunk.id().lastIndexOf(':') + 1));
        } catch (NumberFormatException exception) {
            return Integer.MAX_VALUE;
        }
    }

    /** 独立统计旧分片数量，用于完整 ES 发布校验。 */
    private static int legacyChunkCount(Connection target, String documentId) throws Exception {
        try (PreparedStatement query =
                target.prepareStatement("SELECT COUNT(*) FROM chunks WHERE document_id=?")) {
            query.setString(1, documentId);
            try (ResultSet rows = query.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    /** 固定代码定义的迁移行；调用方不能输入表名、字段名或 SQL。 */
    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            result.put((String) pairs[index], pairs[index + 1]);
        }
        return result;
    }

    /** 迁移记录的所有值都通过 JDBC 参数绑定。 */
    private static void insert(Connection connection, String table, Map<String, Object> values)
            throws Exception {
        try (PreparedStatement write =
                connection.prepareStatement(
                        "INSERT INTO "
                                + table
                                + " ("
                                + String.join(",", values.keySet())
                                + ") VALUES ("
                                + String.join(",", Collections.nCopies(values.size(), "?"))
                                + ")")) {
            int index = 1;
            for (Object value : values.values()) {
                write.setObject(index++, value);
            }
            write.executeUpdate();
        }
    }

    /** 缺少参数时只报告环境变量名称。 */
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("MISSING_ENVIRONMENT_" + name);
        }
        return value;
    }
}
