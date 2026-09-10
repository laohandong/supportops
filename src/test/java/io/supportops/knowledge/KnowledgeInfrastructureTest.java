package io.supportops.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import io.supportops.knowledge.dto.ChunkSource;
import io.supportops.knowledge.dto.ImportOptions;
import io.supportops.knowledge.entity.IndexTaskEntity;
import io.supportops.knowledge.mapper.DocumentReleaseMapper;
import io.supportops.knowledge.mapper.IndexTaskMapper;
import io.supportops.knowledge.mapper.KnowledgeCatalogMapper;
import io.supportops.knowledge.service.DocumentLibraryService;
import io.supportops.knowledge.service.ElasticKnowledgeIndex;
import io.supportops.knowledge.service.ExcelDataService;
import io.supportops.knowledge.service.KnowledgeService;
import io.supportops.knowledge.service.KnowledgeWorker;
import io.supportops.knowledge.service.impl.KnowledgeServiceImpl;
import io.supportops.knowledge.service.impl.OnnxPassageReranker;
import io.supportops.knowledge.service.support.DocumentParser;
import io.supportops.knowledge.service.support.ReadOnlySqlCompiler;
import io.supportops.knowledge.vo.KnowledgeViews;
import io.supportops.persistence.MapperTestDatabase;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import io.supportops.knowledge.vo.KnowledgeSearchResult;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** 使用真实隔离 MySQL、MinIO、ES 验证导入和补偿；嵌入仅使用明确的本地 HTTP 协议夹具。 */
class KnowledgeInfrastructureTest {
    private MapperTestDatabase database;
    private DocumentLibraryService library;
    private KnowledgeService knowledge;
    private ExcelDataService excel;
    private HttpServer provider;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger failAt = new AtomicInteger(-1);
    private final AtomicInteger responseStatus = new AtomicInteger(503);

    /** 每个场景拥有随机数据库、索引前缀和桶，不共享工作台状态。 */
    @BeforeEach
    void setup() throws Exception {
        database = new MapperTestDatabase();
        library = database.library(database.chunks);
        knowledge =
                new KnowledgeServiceImpl(
                        library,
                        database.mapper(KnowledgeCatalogMapper.class),
                        database.mapper(DocumentReleaseMapper.class),
                        database.mapper(IndexTaskMapper.class),
                        database.embedding,
                        database.elastic, new OnnxPassageReranker(database.environment));
        excel = (ExcelDataService) ReflectionTestUtils.getField(library, "excel");
        provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        provider.createContext(
                "/v1/embeddings",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    int status =
                            calls.incrementAndGet() == failAt.get() ? responseStatus.get() : 200;
                    byte[] body =
                            (status == 200
                                            ? "{\"data\":[{\"embedding\":[1,0,0]}]}"
                                            : "{\"error\":\"synthetic-failure\"}")
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        provider.start();
    }

    /** 先关闭协议夹具，再回收本测试持有的真实存储资源。 */
    @AfterEach
    void close() {
        provider.stop(0);
        database.close();
    }

    /** 前导零、日期、空值和精确小数在 XLS/XLSX 多 Sheet 导入与只读 SQL 中保持一致。 */
    @Test
    void excelSheetsPreserveTypesPrecisionSourceRowsAndReadOnlyBoundary() throws Exception {
        for (boolean legacy : List.of(false, true)) {
            KnowledgeViews.Upload upload =
                    upload(
                            legacy ? "orders.xls" : "orders.xlsx",
                            workbook(legacy, 3),
                            ImportOptions.defaults());
            drain();
            List<KnowledgeViews.Dataset> datasets = excel.datasets(upload.batchId());
            assertThat(datasets).hasSize(2).allMatch(dataset -> dataset.status().equals("READY"));
            KnowledgeViews.Dataset data = datasets.getFirst();
            assertThat(data.columns())
                    .extracting(KnowledgeViews.Column::dataType)
                    .containsExactly("TEXT", "TEXT", "DECIMAL", "DATE", "TEXT");
            KnowledgeViews.SqlResult result =
                    excel.query(
                            data.id(),
                            "SELECT c_0,c_2,c_3,c_4 FROM data WHERE c_1='上海' ORDER BY c_2 DESC",
                            "2.0");
            assertThat(result.columns()).containsExactly("c_0", "c_2", "c_3", "c_4", "source_row");
            assertThat(result.rows().getFirst())
                    .containsExactly("000001", "123.4500000000", "2026-09-07", null, "2");
            KnowledgeViews.SqlResult total =
                    excel.query(
                            data.id(), "SELECT SUM(c_2) AS total FROM data WHERE c_1='上海'", "2.0");
            assertThat(total.rows()).containsExactly(List.of("200.0000000000"));
            assertThat(total.versionId()).isEqualTo(upload.versionId());
            try (InputStream original = library.original(upload.versionId())) {
                assertThat(original.readAllBytes()).isEqualTo(workbookBytes);
            }
            try (Connection reader =
                            DriverManager.getConnection(
                                    database.resources.mysql + database.resources.excelSchema,
                                    database.resources.queryUser,
                                    database.resources.queryPassword);
                    Statement sql = reader.createStatement()) {
                String table =
                        database.jdbc.queryForObject(
                                "SELECT table_name FROM excel_datasets WHERE id=?",
                                String.class,
                                data.id());
                assertThatThrownBy(() -> sql.executeUpdate("DELETE FROM " + table))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(
                                () ->
                                        sql.executeQuery(
                                                "SELECT * FROM "
                                                        + database.resources.database
                                                        + ".documents"))
                        .isInstanceOf(SQLException.class);
            }
        }
    }

    /** 预览不建关系表，确认失败不替换已经可查询的数据代。 */
    @Test
    void previewRequiresConfirmationAndFailedOverridePreservesPublishedData() throws Exception {
        ImportOptions preview = new ImportOptions(500, 100, List.of(0), 1, Map.of(), true);
        KnowledgeViews.Upload upload = upload("preview.xlsx", workbook(false, 3), preview);
        drain();
        assertThat(excel.datasets(upload.batchId()))
                .hasSize(1)
                .allMatch(dataset -> dataset.status().equals("PREVIEW"));
        assertThat(excel.applicable("2.0")).isEmpty();
        assertThat(
                        database.jdbc.queryForObject(
                                "SELECT COUNT(*) FROM information_schema.tables WHERE"
                                        + " table_schema=?",
                                Integer.class,
                                database.resources.excelSchema))
                .isZero();
        KnowledgeViews.Batch confirmed =
                library.reprocess(
                        upload.versionId(),
                        new ImportOptions(500, 100, List.of(0), 1, Map.of(), false));
        drain();
        String active = excel.applicable("2.0").getFirst().id();
        library.reprocess(
                upload.versionId(),
                new ImportOptions(500, 100, List.of(0), 1, Map.of("0:1", "INTEGER"), false));
        assertThatThrownBy(this::drain).hasMessageContaining("EXCEL_TYPE_MISMATCH");
        assertThat(excel.applicable("2.0"))
                .extracting(KnowledgeViews.Dataset::id)
                .containsExactly(active);
        assertThat(excel.query(active, "SELECT COUNT(*) AS count FROM data", "2.0").rows())
                .containsExactly(List.of("3"));
        assertThat(
                        database.jdbc.queryForObject(
                                "SELECT batch_id FROM document_releases WHERE document_id=?",
                                String.class,
                                upload.documentId()))
                .isEqualTo(confirmed.id());
    }

    /** 查询只接受注册单表，并标记截断；字符串字面量始终作为数据绑定。 */
    @Test
    void sqlPolicyRejectsBypassAndMarksTruncation() throws Exception {
        KnowledgeViews.Upload upload =
                upload(
                        "many.xlsx",
                        workbook(false, 205),
                        new ImportOptions(750, 100, List.of(0), 1, Map.of(), false));
        drain();
        String id = excel.applicable("2.0").getFirst().id();
        assertThat(excel.query(id, "SELECT * FROM data", "2.0").rows()).hasSize(200);
        assertThat(excel.query(id, "SELECT * FROM data", "2.0").truncated()).isTrue();
        assertThat(
                        excel.query(
                                        id,
                                        "SELECT c_0 FROM data ORDER BY source_row LIMIT 10 OFFSET"
                                                + " 200",
                                        "2.0")
                                .rows())
                .hasSize(5);
        for (String sql :
                List.of(
                        "DELETE FROM data",
                        "SELECT * FROM data; SELECT 1",
                        "SELECT * FROM data UNION SELECT * FROM data",
                        "SELECT * FROM mysql.user",
                        "SELECT * FROM data a JOIN data b ON 1=1",
                        "SELECT SLEEP(1) FROM data",
                        "SELECT * FROM data INTO OUTFILE '/tmp/test'",
                        "SELECT (SELECT 1) FROM data",
                        "SELECT * FROM data FOR UPDATE",
                        "SELECT * FROM data WHERE c_0 IN (SELECT c_0 FROM data)",
                        "SELECT @@version FROM data",
                        "SELECT c_999 FROM data")) {
            assertThatThrownBy(() -> excel.query(id, sql, "2.0"))
                    .as(sql)
                    .hasMessageContaining("SQL_POLICY_REJECTED");
        }
        assertThat(excel.query(id, "SELECT c_0 FROM data WHERE c_0='x'' OR 1=1'", "2.0").rows())
                .isEmpty();
        assertThatThrownBy(() -> excel.query(id, "SELECT SUM(c_2),c_0 FROM data", "2.0"))
                .hasMessage("SQL_QUERY_FAILED");
        assertThatThrownBy(() -> excel.query(id, "SELECT * FROM data", "1.0"))
                .hasMessageContaining("DATASET_NOT_APPLICABLE");
        assertThat(
                        new ReadOnlySqlCompiler()
                                .compile(
                                        "SELECT COUNT(*) FROM data",
                                        database.resources.excelSchema,
                                        "xl_" + id.replace("-", ""),
                                        List.of("c_0"))
                                .sql())
                .doesNotContain("source_row");
        assertThat(library.upload(upload.id()).status()).isEqualTo("SUCCEEDED");
    }

    /** 可配置分片按码点计算，重叠与源位置一致，不切断补充字符。 */
    @Test
    void unicodeChunkMetadataAndTwentyMegabyteLimit() {
        String content = "😀汉字".repeat(500);
        List<DocumentParser.Part> parts =
                new DocumentParser()
                        .parse(
                                "unicode.md",
                                content.getBytes(StandardCharsets.UTF_8),
                                new ImportOptions(300, 75, List.of(), 0, Map.of(), false));
        assertThat(parts).hasSizeGreaterThan(3);
        assertThat(parts)
                .allMatch(part -> part.content().codePointCount(0, part.content().length()) <= 300);
        assertThat(parts.get(1).charStart()).isEqualTo(225);
        assertThat(parts.getFirst().content().codePoints().skip(225).toArray())
                .isEqualTo(parts.get(1).content().codePoints().limit(75).toArray());
        byte[] big = new byte[20 * 1024 * 1024 + 1];
        assertThatThrownBy(() -> upload("too-large.md", big, ImportOptions.defaults()))
                .hasMessageContaining("FILE_TOO_LARGE");
        assertThat(
                        database.jdbc.queryForObject(
                                "SELECT status FROM document_uploads", String.class))
                .isEqualTo("FAILED");
        byte[] medium = ("a".repeat(5 * 1024 * 1024)).getBytes(StandardCharsets.UTF_8);
        KnowledgeViews.Upload accepted =
                upload(
                        "above-old-limit.md",
                        medium,
                        new ImportOptions(8000, 100, List.of(), 0, Map.of(), false));
        assertThat(accepted.sizeBytes()).isEqualTo(medium.length);
        assertThat(accepted.status()).isEqualTo("ACCEPTED");
    }

    /** 分片向量重试复用已确认 ES 写入，仅完整代可以发布。 */
    @Test
    void vectorCompensationResumesAfterFailureWithoutReembeddingSuccessfulChunks() {
        enableEmbedding();
        failAt.set(2);
        KnowledgeViews.Upload upload =
                upload(
                        "retry.md",
                        "# A\nfirst\n# B\nsecond\n# C\nthird".getBytes(StandardCharsets.UTF_8),
                        ImportOptions.defaults());
        assertThatThrownBy(this::drain).hasMessageContaining("EMBEDDING_HTTP_503");
        String task =
                database.jdbc.queryForObject(
                        "SELECT id FROM knowledge_tasks WHERE kind='VECTOR'", String.class);
        assertThat(
                        database.jdbc.queryForObject(
                                "SELECT status FROM knowledge_tasks WHERE id=?",
                                String.class,
                                task))
                .isEqualTo("RETRY_WAIT");
        assertThat(
                        database.jdbc.queryForObject(
                                "SELECT vector_task_id FROM document_releases WHERE document_id=?",
                                String.class,
                                upload.documentId()))
                .isNull();
        assertThat(knowledge.search("first", "2.0", 5, true).passages()).isNotEmpty();
        failAt.set(-1);
        database.jdbc.update("UPDATE knowledge_tasks SET next_retry_at=0 WHERE id=?", task);
        drain();
        assertThat(calls).hasValue(4);
        assertThat(
                        database.jdbc.queryForObject(
                                "SELECT vector_task_id FROM document_releases WHERE document_id=?",
                                String.class,
                                upload.documentId()))
                .isEqualTo(task);
        assertThat(database.tasks.attempts(task))
                .extracting(KnowledgeViews.Attempt::status)
                .containsExactly("RETRY_WAIT", "SUCCEEDED");
        assertThat(knowledge.search("first", "2.0", 5, false).mode()).isEqualTo("HYBRID");
    }

    /** 真实存储双路召回接入真实本地精排；嵌入协议仍使用合成 HTTP 夹具。 */
    @Test
    @EnabledIfSystemProperty(named = "rerank.test.model", matches = ".+")
    void realLocalRerankingWorksAfterHybridAndLexicalRecall() {
        enableEmbedding();
        upload("reranking.md", ("# 迁移规则\n2.0版本停止读取旧配置键，需要迁移配置。\n"
                + "# 其他说明\n配置示例用于演示，食堂今天提供米饭和蔬菜。")
                .getBytes(StandardCharsets.UTF_8), ImportOptions.defaults());
        drain();
        database.environment.withProperty("supportops.reranking.enabled", "true")
                .withProperty("supportops.reranking.model-path", System.getProperty("rerank.test.model"))
                .withProperty("supportops.reranking.tokenizer-path", System.getProperty("rerank.test.tokenizer"));
        try (OnnxPassageReranker local = new OnnxPassageReranker(database.environment)) {
            KnowledgeService ranked = new KnowledgeServiceImpl(library, database.mapper(KnowledgeCatalogMapper.class),
                    database.mapper(DocumentReleaseMapper.class), database.mapper(IndexTaskMapper.class),
                    database.embedding, database.elastic, local);
            for (boolean lexicalOnly : List.of(false, true)) {
                KnowledgeSearchResult result = ranked.search("升级到2.0后旧配置为什么不生效", "2.0", 5, lexicalOnly);
                assertThat(result.mode()).isEqualTo(lexicalOnly ? "LEXICAL" : "HYBRID");
                assertThat(result.ranking().method()).isEqualTo("ONNX");
                assertThat(result.passages()).isNotEmpty().allSatisfy(passage -> {
                    assertThat(passage.rerankScore()).isNotNull().isFinite();
                    assertThat(passage.version()).isEqualTo("2.0");
                    assertThat(passage.versionId()).isNotBlank();
                    assertThat(passage.originalUrl()).contains(passage.versionId());
                });
                assertThat(result.passages().getFirst().content()).contains("停止读取旧配置键");
            }
        }
    }

    /** 到期租约不可复活，旧执行者无法发布，人工重试保留历史并开启新预算。 */
    @Test
    void expiredLeaseFencesOldWorkerAndRetryBudgetCanBeRenewed() {
        upload(
                "lease.md",
                "# lease\nlease test".getBytes(StandardCharsets.UTF_8),
                ImportOptions.defaults());
        IndexTaskEntity first = database.tasks.claim();
        database.jdbc.update("UPDATE knowledge_tasks SET lease_until=0 WHERE id=?", first.getId());
        IndexTaskEntity second = database.tasks.claim();
        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getLeaseOwner()).isNotEqualTo(first.getLeaseOwner());
        assertThat(database.tasks.heartbeat(first)).isFalse();
        assertThatThrownBy(() -> database.tasks.requireLease(first))
                .hasMessageContaining("TASK_LEASE_LOST");
        database.tasks.finish(first, null);
        assertThat(
                        database.jdbc.queryForObject(
                                "SELECT status FROM knowledge_tasks WHERE id=?",
                                String.class,
                                first.getId()))
                .isEqualTo("RUNNING");
        database.jdbc.update(
                "UPDATE knowledge_tasks SET attempt_count=5,lease_until=0 WHERE id=?",
                second.getId());
        assertThat(database.tasks.claim()).isNull();
        assertThat(
                        database.jdbc.queryForObject(
                                "SELECT status FROM knowledge_tasks WHERE id=?",
                                String.class,
                                first.getId()))
                .isEqualTo("FAILED");
        database.tasks.retry(first.getId());
        IndexTaskEntity renewed = database.tasks.claim();
        assertThat(renewed.getAttemptCount()).isEqualTo(6);
        assertThat(renewed.getBudgetStart()).isEqualTo(5);
        library.execute(renewed);
        database.tasks.finish(renewed, null);
        drain();
        assertThat(database.tasks.attempts(first.getId())).hasSize(3);
    }

    /** 新修订失败保留旧发布资料，重复请求可核对而不能更改其正文。 */
    @Test
    void idempotentUploadAndVersionPublicationKeepPreviousDataUntilReady() {
        byte[] text = "# old\nunique-old-evidence".getBytes(StandardCharsets.UTF_8);
        String key = UUID.randomUUID().toString();
        KnowledgeViews.Upload first =
                library.submit(
                        "version.md",
                        "版本验证",
                        "2.0",
                        null,
                        "",
                        key,
                        "TEST",
                        text,
                        ImportOptions.defaults());
        drain();
        KnowledgeViews.Upload duplicate =
                library.submit(
                        "version.md",
                        "版本验证",
                        "2.0",
                        null,
                        "",
                        key,
                        "TEST",
                        text,
                        ImportOptions.defaults());
        assertThat(duplicate.id()).isEqualTo(first.id());
        assertThatThrownBy(
                        () ->
                                library.submit(
                                        "version.md",
                                        "版本验证",
                                        "2.0",
                                        null,
                                        "",
                                        key,
                                        "TEST",
                                        new byte[] {1},
                                        ImportOptions.defaults()))
                .hasMessageContaining("UPLOAD_KEY_CONFLICT");
        KnowledgeViews.Upload broken =
                library.submit(
                        "version.md",
                        "版本验证",
                        "2.0",
                        first.documentId(),
                        "修订 2",
                        null,
                        "TEST",
                        new byte[] {(byte) 0xff},
                        ImportOptions.defaults());
        assertThatThrownBy(this::drain).hasMessageContaining("DOCUMENT_MUST_BE_UTF8");
        assertThat(knowledge.search("unique-old-evidence", "2.0", 5, true).passages())
                .allMatch(passage -> passage.versionId().equals(first.versionId()));
        assertThat(library.versions(first.documentId())).hasSize(2);
        assertThat(library.upload(broken.id()).status()).isEqualTo("FAILED");
    }

    /** 调度器在虚拟线程执行外部写入，关闭后数据库仍保留完整完成状态。 */
    @Test
    void scheduledWorkerUsesVirtualThreadsAndAutomaticallyPublishes() throws Exception {
        enableEmbedding();
        AtomicBoolean virtual = new AtomicBoolean();
        ElasticKnowledgeIndex observed =
                new ElasticKnowledgeIndex(
                        new ObjectMapper(),
                        database.resources.es,
                        "",
                        "",
                        database.resources.prefix) {
                    /** 在真实外部索引写入调用点核对工作线程类型。 */
                    @Override
                    public void putVector(
                            String index,
                            String taskId,
                            ChunkSource source,
                            double[] vector,
                            int epoch) {
                        virtual.set(Thread.currentThread().isVirtual());
                        super.putVector(index, taskId, source, vector, epoch);
                    }
                };
        ReflectionTestUtils.setField(
                ReflectionTestUtils.getField(library, "indexing"), "elastic", observed);
        KnowledgeWorker worker =
                new KnowledgeWorker(database.tasks, library, true, 2, 100, 100, 30000);
        try {
            worker.start();
            KnowledgeViews.Upload upload =
                    upload(
                            "virtual.md",
                            "# virtual\nvirtual thread evidence".getBytes(StandardCharsets.UTF_8),
                            ImportOptions.defaults());
            await(
                    () ->
                            database.jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM knowledge_tasks WHERE"
                                                    + " document_id=? AND kind='VECTOR' AND"
                                                    + " status='SUCCEEDED'",
                                            Integer.class,
                                            upload.documentId())
                                    == 1);
            assertThat(virtual).isTrue();
            assertThat(knowledge.search("virtual", "2.0", 5, false).passages()).isNotEmpty();
        } finally {
            worker.close();
        }
    }

    /** 删除发生在外部写入之后时，迟到线程不能发布，清理会移除真实存储对象。 */
    @Test
    void deletionFencesLateVectorAndCleanupRetainsAudit() {
        enableEmbedding();
        KnowledgeViews.Upload upload =
                upload(
                        "delete.md",
                        "# delete\ndelete-late-evidence".getBytes(StandardCharsets.UTF_8),
                        ImportOptions.defaults());
        ElasticKnowledgeIndex deleting =
                new ElasticKnowledgeIndex(
                        new ObjectMapper(),
                        database.resources.es,
                        "",
                        "",
                        database.resources.prefix) {
                    /** 在真实 ES 写入完成与数据库发布之间注入文档删除。 */
                    @Override
                    public void putVector(
                            String index,
                            String taskId,
                            ChunkSource source,
                            double[] vector,
                            int epoch) {
                        super.putVector(index, taskId, source, vector, epoch);
                        library.delete(upload.documentId());
                    }
                };
        ReflectionTestUtils.setField(
                ReflectionTestUtils.getField(library, "indexing"), "elastic", deleting);
        assertThatThrownBy(this::drain).hasMessageContaining("TASK_LEASE_LOST");
        assertThat(knowledge.search("delete-late-evidence", "2.0", 5, true).passages()).isEmpty();
        assertThat(
                        database.jdbc.queryForObject(
                                "SELECT COUNT(*) FROM document_releases", Integer.class))
                .isZero();
        assertThat(
                        database.jdbc.queryForObject(
                                "SELECT COUNT(*) FROM knowledge_attempts WHERE status='RUNNING'",
                                Integer.class))
                .isZero();
        database.jdbc.update("UPDATE knowledge_tasks SET next_retry_at=0 WHERE kind='CLEANUP'");
        drain();
        assertThat(database.jdbc.queryForObject("SELECT status FROM source_files", String.class))
                .isEqualTo("DELETED");
        assertThat(library.upload(upload.id()).status()).isEqualTo("SUCCEEDED");
        assertThat(database.jdbc.queryForObject("SELECT COUNT(*) FROM chunks", Integer.class))
                .isEqualTo(1);
        assertThat(
                        database.elastic.lexical(
                                "delete-late-evidence", "2.0", List.of(upload.batchId()), 5))
                .isEmpty();
    }

    /** 缺失原件只有相同字节才能恢复，成功与失败补传都留下上传记录。 */
    @Test
    void restoringOriginalVerifiesChecksumAndKeepsUploadAudit() throws Exception {
        byte[] content = "# original\nrestore-evidence".getBytes(StandardCharsets.UTF_8);
        KnowledgeViews.Upload upload = upload("restore.md", content, ImportOptions.defaults());
        drain();
        database.jdbc.update("UPDATE source_files SET status='MISSING'");
        assertThatThrownBy(
                        () ->
                                library.restoreOriginal(
                                        upload.versionId(),
                                        "different".getBytes(StandardCharsets.UTF_8)))
                .hasMessageContaining("ORIGINAL_CHECKSUM_MISMATCH");
        library.restoreOriginal(upload.versionId(), content);
        try (InputStream restored = library.original(upload.versionId())) {
            assertThat(restored.readAllBytes()).isEqualTo(content);
        }
        assertThat(
                        database.jdbc.queryForList(
                                "SELECT status FROM document_uploads WHERE source='RESTORE' ORDER"
                                        + " BY created_at",
                                String.class))
                .containsExactly("FAILED", "SUCCEEDED");
        assertThat(library.versions(upload.documentId())).hasSize(1);
    }

    private byte[] workbookBytes;

    /** 生成合成工作簿，POI 仅用于制造验证样例，生产解析仍使用 EasyExcel。 */
    private byte[] workbook(boolean legacy, int count) throws Exception {
        try (Workbook workbook = legacy ? new HSSFWorkbook() : new XSSFWorkbook();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("订单数据");
            Row header = sheet.createRow(0);
            List<String> labels = List.of("订单编号", "地区", "金额", "发生日期", "可空说明");
            for (int index = 0; index < labels.size(); index++) {
                header.createCell(index).setCellValue(labels.get(index));
            }
            CellStyle date = workbook.createCellStyle();
            date.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd"));
            for (int index = 0; index < count; index++) {
                Row row = sheet.createRow(index + 1);
                row.createCell(0).setCellValue(String.format("%06d", index + 1));
                row.createCell(1).setCellValue(index % 2 == 0 ? "上海" : "北京");
                row.createCell(2).setCellValue(index == 0 ? 123.45 : index == 2 ? 76.55 : 200.10);
                Cell cell = row.createCell(3);
                cell.setCellValue(LocalDateTime.of(2026, 9, 7, 0, 0));
                cell.setCellStyle(date);
            }
            Sheet second = workbook.createSheet("说明");
            second.createRow(0).createCell(0).setCellValue("内容");
            second.createRow(1).createCell(0).setCellValue("合成测试资料");
            workbook.write(output);
            workbookBytes = output.toByteArray();
            return workbookBytes;
        }
    }

    /** 使用正式上传流程，只有幂等键由测试生成。 */
    private KnowledgeViews.Upload upload(String name, byte[] bytes, ImportOptions options) {
        return library.submit(
                name, name, "2.0", null, "", UUID.randomUUID().toString(), "TEST", bytes, options);
    }

    /** 固定到本地协议夹具，不使用本机实际模型凭据。 */
    private void enableEmbedding() {
        database.environment
                .withProperty("supportops.embedding.provider", "custom")
                .withProperty(
                        "supportops.embedding.base-url",
                        "http://127.0.0.1:" + provider.getAddress().getPort() + "/v1")
                .withProperty("supportops.embedding.name", "test-vector")
                .withProperty("supportops.embedding.api-key", "synthetic-test");
    }

    /** 真实任务的确定性驱动，保留错误状态后让断言检查原异常。 */
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

    /** 等待异步持久化条件，超时明确失败。 */
    private void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }
}
