package io.supportops.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.supportops.agent.entity.DiagnosisRunEntity;
import io.supportops.agent.mapper.DiagnosisRunMapper;
import io.supportops.agent.service.DiagnosticEngine;
import io.supportops.agent.service.DiagnosticEngine.Context;
import io.supportops.agent.service.DiagnosticEngine.Result;
import io.supportops.agent.service.impl.RunServiceImpl;
import io.supportops.agent.service.support.RunStreamSignals;
import io.supportops.agent.vo.DiagnosisRun;
import io.supportops.knowledge.entity.IndexTaskEntity;
import io.supportops.knowledge.mapper.DocumentChunkMapper;
import io.supportops.knowledge.mapper.DocumentReleaseMapper;
import io.supportops.knowledge.mapper.IndexTaskMapper;
import io.supportops.knowledge.mapper.KnowledgeCatalogMapper;
import io.supportops.knowledge.service.DocumentLibraryService;
import io.supportops.knowledge.service.KnowledgeService;
import io.supportops.knowledge.service.impl.KnowledgeServiceImpl;
import io.supportops.knowledge.service.impl.OnnxPassageReranker;
import io.supportops.memory.service.impl.MemoryServiceImpl;
import io.supportops.migration.H2ToMysqlMigration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/** 验证迁移后的真实 SQL、旧结构兼容、事务回滚和并发更新；不访问当前工作台数据。 */
class MybatisPersistenceTest {
    private MapperTestDatabase database;
    private KnowledgeService knowledge;
    private DocumentLibraryService library;
    private final ObjectMapper json = new ObjectMapper();

    /** 使用原表结构与无向量凭据环境创建隔离的业务服务。 */
    @BeforeEach
    void setup() throws Exception {
        database = new MapperTestDatabase();
        knowledge = knowledge(database.chunks);
    }

    /** 所有验证结束后释放隔离数据库。 */
    @AfterEach
    void cleanup() {
        database.close();
    }

    /** 根据指定分片 Mapper 构造正式服务，支持仅对某次 SQL 注入失败。 */
    private KnowledgeService knowledge(DocumentChunkMapper chunks) {
        library = database.library(chunks);
        return new KnowledgeServiceImpl(
                library,
                database.mapper(KnowledgeCatalogMapper.class),
                database.mapper(DocumentReleaseMapper.class),
                database.mapper(IndexTaskMapper.class),
                database.embedding,
                database.elastic, new OnnxPassageReranker(database.environment));
    }

    /** 确定性地执行真实持久化任务，异常仍按正式流程记录后抛给断言。 */
    private void drain() {
        IndexTaskEntity task;
        while ((task = database.tasks.claim()) != null) {
            try {
                library.execute(task);
                database.tasks.finish(task, null);
            } catch (RuntimeException exception) {
                database.tasks.finish(task, exception);
                throw exception;
            }
        }
    }

    /** 通过旧 JDBC 写入方式造数据，核对 CLOB、空值、UUID、时间与投影字段的兼容性。 */
    @Test
    void readsLegacySchemaWithoutChangingIdsTextOrNullValues() throws Exception {
        DriverManagerDataSource legacy =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        createLegacyFixture(legacy);
        JdbcTemplate old = new JdbcTemplate(legacy);
        String documentId = UUID.randomUUID().toString(),
                memoryId = UUID.randomUUID().toString(),
                runId = UUID.randomUUID().toString();
        String created = "2026-01-01T00:00:00Z";
        old.update(
                "INSERT INTO documents VALUES (?,?,?,?,?,?,?)",
                documentId,
                "旧文档",
                "2.0",
                "legacy.md",
                "legacy-checksum",
                created,
                "legacy|model");
        old.update(
                "INSERT INTO chunks VALUES (?,?,?,?,?)",
                documentId + ":1",
                documentId,
                "迁移章节 · 第 1 页",
                "sync.targetPath 保留旧正文",
                "[1,0,0]");
        old.update("INSERT INTO memories VALUES (?,?,?)", memoryId, "已确认的旧约束", created);
        old.update(
                "INSERT INTO runs(id,session_id,question,status,answer,error_code,created_at)"
                        + " VALUES (?,?,?,?,?,?,?)",
                runId,
                runId,
                "旧任务",
                "RUNNING",
                "",
                "",
                created);
        old.update(
                "INSERT INTO run_events(id,run_id,kind,content,created_at) VALUES (?,?,?,?,?)",
                41L,
                runId,
                "TOOL_RESULT",
                "{\"text\":\"旧证据\"}",
                created);
        try (Connection source = legacy.getConnection();
                Connection target = database.jdbc.getDataSource().getConnection()) {
            assertThat(H2ToMysqlMigration.migrate(source, target))
                    .containsEntry("documents", 1L)
                    .containsEntry("run_events", 1L);
            assertThatThrownBy(() -> H2ToMysqlMigration.migrate(source, target))
                    .hasMessageContaining("MIGRATION_TARGET_NOT_EMPTY");
        }
        assertThat(old.queryForObject("SELECT COUNT(*) FROM documents", Integer.class))
                .isEqualTo(1);
        assertThat(old.queryForObject("SELECT embedding FROM chunks", String.class))
                .isEqualTo("[1,0,0]");
        drain();
        assertThat(database.runs.selectById(runId).getFinishedAt()).isNull();
        assertThat(database.runs.selectById(runId).getInputTokens()).isZero();
        assertThat(knowledge.document(documentId).title()).isEqualTo("旧文档");
        assertThat(knowledge.document(documentId).createdAt()).isEqualTo(created);
        // 迁移占位参数不是旧分片实际参数；界面和 API 必须如实返回未知。
        assertThat(library.batches(documentId).getFirst().chunkSize()).isNull();
        assertThat(library.batches(documentId).getFirst().overlap()).isNull();
        assertThat(
                        database.jdbc.queryForObject(
                                "SELECT CAST(JSON_EXTRACT(config_json,'$.chunkSize') AS UNSIGNED) ="
                                    + " chunk_size AND CAST(JSON_EXTRACT(config_json,'$.overlap')"
                                    + " AS UNSIGNED) = overlap FROM document_batches",
                                Integer.class))
                .isEqualTo(1);
        assertThat(knowledge.content(documentId).getFirst().content())
                .isEqualTo("sync.targetPath 保留旧正文");
        assertThat(knowledge.search("sync.targetPath", "2.0", 5, true).passages().getFirst().id())
                .isEqualTo(documentId + ":1");
        assertThat(new MemoryServiceImpl(database.memories).list().getFirst().id())
                .isEqualTo(memoryId);
        assertThat(
                        database.jdbc.queryForObject(
                                "SELECT content FROM run_events WHERE id=41", String.class))
                .contains("旧证据");
        assertThat(database.jdbc.queryForObject("SELECT status FROM source_files", String.class))
                .isEqualTo("MISSING");
        assertThatThrownBy(() -> library.original(knowledge.document(documentId).versionId()))
                .hasMessageContaining("ORIGINAL_FILE_MISSING");
        knowledge.delete(documentId);
        assertThat(
                        database.jdbc.queryForObject(
                                "SELECT COUNT(*) FROM chunks WHERE document_id=?",
                                Integer.class,
                                documentId))
                .isEqualTo(1);
        assertThat(knowledge.search("sync.targetPath", "2.0", 5, true).passages()).isEmpty();
        old.execute("SHUTDOWN");
    }

    /** 第二个分片写入失败时，所有分片必须回滚，文档、原件和失败上传事实保留供补偿。 */
    @Test
    void chunkInsertFailureRollsBackDocumentAndEarlierChunks() {
        AtomicInteger inserts = new AtomicInteger();
        DocumentChunkMapper failing =
                MapperTestDatabase.intercept(
                        DocumentChunkMapper.class,
                        database.chunks,
                        (method, arguments) -> {
                            if (method.getName().equals("insert")
                                    && inserts.incrementAndGet() == 2) {
                                throw new IllegalStateException("INJECTED_CHUNK_FAILURE");
                            }
                        });
        knowledge(failing)
                .ingest(
                        "rollback.md",
                        "回滚验证",
                        "2.0",
                        ("# 事务验证\n" + "sync.targetPath ".repeat(100))
                                .getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(this::drain).hasMessageContaining("INJECTED_CHUNK_FAILURE");
        assertThat(inserts).hasValue(2);
        assertThat(database.jdbc.queryForObject("SELECT COUNT(*) FROM documents", Integer.class))
                .isEqualTo(1);
        assertThat(database.jdbc.queryForObject("SELECT COUNT(*) FROM chunks", Integer.class))
                .isZero();
    }

    /** 同时上传相同内容时，唯一约束和回滚后的重读应返回同一份文档。 */
    @Test
    void concurrentDuplicateUploadsKeepOneDocumentAndOneChunkSet() throws Exception {
        byte[] bytes = "# 并发上传\nsync.targetPath migration".getBytes(StandardCharsets.UTF_8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> uploads = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(6)) {
            for (int index = 0; index < 6; index++) {
                uploads.add(
                        executor.submit(
                                () -> {
                                    start.await();
                                    return knowledge.ingest("same.md", "同一文档", "2.0", bytes).id();
                                }));
            }
            start.countDown();
            List<String> ids = new ArrayList<>();
            for (Future<String> upload : uploads) {
                ids.add(upload.get(10, TimeUnit.SECONDS));
            }
            assertThat(ids.stream().distinct().count()).isEqualTo(1);
        }
        drain();
        assertThat(database.jdbc.queryForObject("SELECT COUNT(*) FROM documents", Integer.class))
                .isEqualTo(1);
        assertThat(database.jdbc.queryForObject("SELECT COUNT(*) FROM chunks", Integer.class))
                .isEqualTo(1);
    }

    /** 并发用量按 SQL 原子累加，终态写入后拒绝迟到状态和用量覆盖。 */
    @Test
    void concurrentUsageIsNotLostAndTerminalStateCannotBeOverwritten() throws Exception {
        String id = insertRun("RUNNING");
        List<Future<Integer>> updates = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            for (int index = 0; index < 40; index++) {
                updates.add(executor.submit(() -> database.runs.incrementUsage(id, 3, 2)));
            }
            for (Future<Integer> update : updates) {
                assertThat(update.get(10, TimeUnit.SECONDS)).isEqualTo(1);
            }
        }
        assertThat(
                        database.jdbc.queryForObject(
                                "SELECT input_tokens FROM runs WHERE id=?", Long.class, id))
                .isEqualTo(120);
        assertThat(
                        database.jdbc.queryForObject(
                                "SELECT output_tokens FROM runs WHERE id=?", Long.class, id))
                .isEqualTo(80);
        DiagnosisRunEntity record = database.runs.selectById(id);
        record.setStatus("COMPLETED");
        record.setAnswer("已经完成");
        record.setFinishedAt("2026-01-01T00:01:00Z");
        assertThat(database.runs.finishActiveRun(record)).isEqualTo(1);
        record.setStatus("FAILED");
        assertThat(database.runs.finishActiveRun(record)).isZero();
        assertThat(database.runs.incrementUsage(id, 99, 99)).isZero();
        assertThat(
                        database.jdbc.queryForObject(
                                "SELECT status FROM runs WHERE id=?", String.class, id))
                .isEqualTo("COMPLETED");
    }

    /** 用量更新失败必须回滚此前写入的 USAGE 事件，避免事件与任务计量不一致。 */
    @Test
    void usageFailureRollsBackEventAndRecordsTaskFailure() throws Exception {
        DiagnosisRunMapper failing =
                MapperTestDatabase.intercept(
                        DiagnosisRunMapper.class,
                        database.runs,
                        (method, arguments) -> {
                            if (method.getName().equals("incrementUsage")) {
                                throw new IllegalStateException("INJECTED_USAGE_FAILURE");
                            }
                        });
        RunServiceImpl harness =
                new RunServiceImpl(
                        failing,
                        database.events,
                        database.transactions,
                        json,
                        new UsageEngine(),
                        new MemoryServiceImpl(database.memories),
                        new RunStreamSignals(),
                        30);
        try {
            DiagnosisRun run = harness.start("验证用量事务", null, true);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (ACTIVE_STATUSES.contains(harness.get(run.id()).status())
                    && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(harness.get(run.id()).status()).isEqualTo("FAILED");
            assertThat(
                            database.jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM run_events WHERE run_id=? AND"
                                            + " kind='USAGE'",
                                    Integer.class,
                                    run.id()))
                    .isZero();
            assertThat(
                            database.jdbc.queryForObject(
                                    "SELECT input_tokens FROM runs WHERE id=?",
                                    Long.class,
                                    run.id()))
                    .isZero();
            assertThat(
                            database.jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM run_events WHERE run_id=? AND"
                                            + " kind='ERROR'",
                                    Integer.class,
                                    run.id()))
                    .isEqualTo(1);
        } finally {
            harness.close();
        }
    }

    private static final Set<String> ACTIVE_STATUSES = Set.of("QUEUED", "RUNNING");

    /** 创建与旧 JDBC 实现相同的任务记录，保留数据库默认计量和空终态时间。 */
    private String insertRun(String status) {
        String id = UUID.randomUUID().toString();
        database.jdbc.update(
                "INSERT INTO runs(id,session_id,question,status,answer,error_code,created_at)"
                        + " VALUES (?,?,?,?,?,?,?)",
                id,
                UUID.randomUUID().toString(),
                "旧任务",
                status,
                "",
                "",
                "2026-01-01T00:00:00Z");
        return id;
    }

    /** 只报告用量的执行夹具，用于检查真实数据库事务，不生成诊断结论。 */
    private static class UsageEngine implements DiagnosticEngine {
        /** 允许测试触发执行，不检查真实模型配置。 */
        @Override
        public boolean configured() {
            return true;
        }

        /** 返回仅供测试识别的名称。 */
        @Override
        public String modelName() {
            return "usage-transaction-fixture";
        }

        /** 报告一次用量，由故障注入验证事件和计量的共同回滚。 */
        @Override
        public Result execute(Context context, BiConsumer<String, Object> events) {
            events.accept("USAGE", Map.of("inputTokens", 12, "outputTokens", 3));
            return new Result("不应完成", 12, 3, false);
        }
    }

    /** 仅为离线迁移测试构造旧 H2 输入，保留 CLOB、事件自增和外键语义；不打包到应用。 */
    private void createLegacyFixture(DriverManagerDataSource legacy) {
        String schema =
                """
CREATE TABLE IF NOT EXISTS documents (
    id VARCHAR(36) PRIMARY KEY,
    title VARCHAR(180) NOT NULL,
    version VARCHAR(30) NOT NULL,
    filename VARCHAR(200) NOT NULL,
    checksum VARCHAR(64) NOT NULL,
    created_at VARCHAR(40) NOT NULL,
    embedding_key VARCHAR(500) NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS document_content_version ON documents(checksum, version);

CREATE TABLE IF NOT EXISTS chunks (
    id VARCHAR(50) PRIMARY KEY,
    document_id VARCHAR(36) NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    location VARCHAR(160) NOT NULL,
    content CLOB NOT NULL,
    embedding CLOB
);

CREATE TABLE IF NOT EXISTS memories (
    id VARCHAR(36) PRIMARY KEY,
    content VARCHAR(1000) NOT NULL,
    created_at VARCHAR(40) NOT NULL
);

CREATE TABLE IF NOT EXISTS runs (
    id VARCHAR(36) PRIMARY KEY,
    session_id VARCHAR(36) NOT NULL,
    question VARCHAR(6000) NOT NULL,
    status VARCHAR(30) NOT NULL,
    answer CLOB NOT NULL,
    error_code VARCHAR(80) NOT NULL,
    created_at VARCHAR(40) NOT NULL,
    finished_at VARCHAR(40),
    elapsed_ms BIGINT NOT NULL DEFAULT 0,
    input_tokens BIGINT NOT NULL DEFAULT 0,
    output_tokens BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS run_events (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL REFERENCES runs(id),
    kind VARCHAR(60) NOT NULL,
    content CLOB NOT NULL,
    created_at VARCHAR(40) NOT NULL
);
CREATE INDEX IF NOT EXISTS run_events_by_run ON run_events(run_id, id);
""";
        new ResourceDatabasePopulator(
                        new ByteArrayResource(schema.getBytes(StandardCharsets.UTF_8)))
                .execute(legacy);
    }
}
