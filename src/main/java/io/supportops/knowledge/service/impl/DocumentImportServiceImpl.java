package io.supportops.knowledge.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.supportops.knowledge.constant.KnowledgeLimits;
import io.supportops.knowledge.dto.ImportOptions;
import io.supportops.knowledge.entity.DocumentChunkEntity;
import io.supportops.knowledge.entity.DocumentVersionEntity;
import io.supportops.knowledge.entity.IndexTaskEntity;
import io.supportops.knowledge.entity.ProcessingBatchEntity;
import io.supportops.knowledge.entity.UploadRecordEntity;
import io.supportops.knowledge.mapper.DocumentChunkMapper;
import io.supportops.knowledge.mapper.DocumentVersionMapper;
import io.supportops.knowledge.mapper.ProcessingBatchMapper;
import io.supportops.knowledge.mapper.SourceFileMapper;
import io.supportops.knowledge.mapper.UploadRecordMapper;
import io.supportops.knowledge.service.DocumentImportService;
import io.supportops.knowledge.service.DocumentRevisionService;
import io.supportops.knowledge.service.ExcelDataService;
import io.supportops.knowledge.service.KnowledgeTaskService;
import io.supportops.knowledge.service.support.DocumentParser;
import io.supportops.knowledge.service.support.KnowledgeValues;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;

/** 原件解析与分片入库阶段；不承担 ES 和模型调用，完整解析结果是后续索引的输入。 */
@Service
public class DocumentImportServiceImpl implements DocumentImportService {
    private final SourceFileMapper files;
    private final UploadRecordMapper uploads;
    private final DocumentVersionMapper versions;
    private final ProcessingBatchMapper batches;
    private final DocumentChunkMapper chunks;
    private final KnowledgeTaskService tasks;
    private final DocumentParser parser;
    private final ExcelDataService excel;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final DocumentRevisionService revisions;

    /** 注入本阶段使用的持久化、事务及外部服务边界。 */
    public DocumentImportServiceImpl(
            SourceFileMapper files,
            UploadRecordMapper uploads,
            DocumentVersionMapper versions,
            ProcessingBatchMapper batches,
            DocumentChunkMapper chunks,
            KnowledgeTaskService tasks,
            DocumentParser parser,
            ExcelDataService excel,
            TransactionTemplate transactions,
            ObjectMapper json,
            DocumentRevisionService revisions) {
        this.files = files;
        this.uploads = uploads;
        this.versions = versions;
        this.batches = batches;
        this.chunks = chunks;
        this.tasks = tasks;
        this.parser = parser;
        this.excel = excel;
        this.transactions = transactions;
        this.json = json;
        this.revisions = revisions;
    }

    /** 恢复批次快照并执行解析；外部读取与解析不占用数据库事务。 */
    @Override
    public void importBatch(IndexTaskEntity task, ProcessingBatchEntity batch) {
        if (Set.of("READY", "PREVIEW").contains(batch.getStatus())) {
            return;
        }
        DocumentVersionEntity version = versions.selectById(batch.getVersionId());
        ImportOptions options = decode(batch.getConfigJson());
        markBatchRunning(task, batch);
        tasks.progress(task, "PARSING", 0);
        try {
            // Excel 发布关系数据；文本发布分片后才能进入关键词和向量阶段。
            if (isExcel(version)) {
                importExcel(task, batch, version, options);
            } else {
                importText(task, batch, version, options);
            }
        } catch (Exception exception) {
            markImportFailed(task, batch, exception);
            if (exception instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("DOCUMENT_PARSE_FAILED");
        }
    }

    /** 文档锁与租约在同一事务核对，取消或失去执行权的线程不能推进批次。 */
    private void markBatchRunning(IndexTaskEntity task, ProcessingBatchEntity batch) {
        transactions.executeWithoutResult(
                transaction -> {
                    revisions.requireDocument(batch.getDocumentId(), true);
                    tasks.requireLease(task);
                    batches.update(
                            Wrappers.<ProcessingBatchEntity>lambdaUpdate()
                                    .eq(ProcessingBatchEntity::getId, batch.getId())
                                    .notIn(
                                            ProcessingBatchEntity::getStatus,
                                            List.of("READY", "PREVIEW", "CANCELLED"))
                                    .set(ProcessingBatchEntity::getStatus, "RUNNING"));
                });
    }

    /** 所有 Sheet 导入完成后才切换发布指针；预览只保存类型及样例。 */
    private void importExcel(
            IndexTaskEntity task,
            ProcessingBatchEntity batch,
            DocumentVersionEntity version,
            ImportOptions options) {
        long rows = excel.ingest(batch, options, () -> revisions.original(version.getId()), task);
        transactions.executeWithoutResult(
                transaction -> {
                    revisions.requireDocument(batch.getDocumentId(), true);
                    tasks.requireLease(task);
                    batch.setRowCount(rows);
                    batch.setStatus(options.preview() ? "PREVIEW" : "READY");
                    batches.updateById(batch);
                    if (!options.preview()) {
                        revisions.publish(batch, null);
                    }
                    finishUploads(batch);
                });
    }

    /** 从不可变原件恢复正文，沿用该批次上传时保存的分片参数。 */
    private void importText(
            IndexTaskEntity task,
            ProcessingBatchEntity batch,
            DocumentVersionEntity version,
            ImportOptions options)
            throws IOException {
        byte[] bytes;
        try (InputStream input = revisions.original(version.getId())) {
            bytes = input.readNBytes(KnowledgeLimits.FILE_BYTES + 1);
        }
        if (bytes.length > KnowledgeLimits.FILE_BYTES) {
            throw invalid("INVALID_DOCUMENT");
        }
        String filename = files.selectById(version.getFileId()).getFilename();
        List<DocumentParser.Part> parts = parser.parse(filename, bytes, options);
        saveChunksAndScheduleText(task, batch, parts);
    }

    /** 完整分片和 TEXT 任务原子提交，避免出现分片已保存却永远没有索引任务的状态。 */
    private void saveChunksAndScheduleText(
            IndexTaskEntity task, ProcessingBatchEntity batch, List<DocumentParser.Part> parts) {
        transactions.executeWithoutResult(
                transaction -> {
                    revisions.requireDocument(batch.getDocumentId(), true);
                    tasks.requireLease(task);
                    // 仅替换当前未发布批次；旧修订和旧批次仍能支撑已有引用。
                    chunks.delete(
                            Wrappers.<DocumentChunkEntity>lambdaQuery()
                                    .eq(DocumentChunkEntity::getBatchId, batch.getId()));
                    for (int index = 0; index < parts.size(); index++) {
                        chunks.insert(chunk(batch, parts.get(index), index + 1));
                    }
                    batch.setChunkCount(parts.size());
                    batch.setStatus("READY");
                    batch.setErrorCode("");
                    batches.updateById(batch);
                    tasks.create(batch, "TEXT", "");
                    finishUploads(batch);
                });
    }

    /** 记录解析失败；再次核对租约，防止旧执行者覆盖新的成功或取消结果。 */
    private void markImportFailed(
            IndexTaskEntity task, ProcessingBatchEntity batch, Exception error) {
        transactions.executeWithoutResult(
                transaction -> {
                    revisions.requireDocument(batch.getDocumentId(), true);
                    tasks.requireLease(task);
                    String code = KnowledgeValues.error(error);
                    batches.update(
                            Wrappers.<ProcessingBatchEntity>lambdaUpdate()
                                    .eq(ProcessingBatchEntity::getId, batch.getId())
                                    .ne(ProcessingBatchEntity::getStatus, "CANCELLED")
                                    .set(ProcessingBatchEntity::getStatus, "FAILED")
                                    .set(ProcessingBatchEntity::getErrorCode, code));
                    uploads.update(
                            Wrappers.<UploadRecordEntity>lambdaUpdate()
                                    .eq(UploadRecordEntity::getBatchId, batch.getId())
                                    .set(UploadRecordEntity::getStatus, "FAILED")
                                    .set(UploadRecordEntity::getErrorCode, code)
                                    .set(UploadRecordEntity::getFinishedAt, KnowledgeValues.now()));
                });
    }

    /** 固定分片 ID 含批次，重新分片不会修改历史引用。 */
    private DocumentChunkEntity chunk(
            ProcessingBatchEntity batch, DocumentParser.Part part, int index) {
        DocumentChunkEntity chunk =
                new DocumentChunkEntity(
                        batch.getId() + ":" + index,
                        batch.getDocumentId(),
                        part.location(),
                        part.content(),
                        null);
        chunk.setVersionId(batch.getVersionId());
        chunk.setBatchId(batch.getId());
        chunk.setChunkIndex(index);
        chunk.setHeading(part.heading());
        chunk.setHeadingPath(part.headingPath());
        chunk.setPageStart(part.pageStart());
        chunk.setPageEnd(part.pageEnd());
        chunk.setLineStart(part.lineStart());
        chunk.setLineEnd(part.lineEnd());
        chunk.setCharStart(part.charStart());
        chunk.setCharEnd(part.charEnd());
        chunk.setContentHash(KnowledgeValues.hash(part.content()));
        chunk.setCharCount(part.content().codePointCount(0, part.content().length()));
        return chunk;
    }

    /** 上传处理成功只表示解析结果完整，索引状态另行展示。 */
    private void finishUploads(ProcessingBatchEntity batch) {
        uploads.update(
                Wrappers.<UploadRecordEntity>lambdaUpdate()
                        .eq(UploadRecordEntity::getBatchId, batch.getId())
                        .ne(UploadRecordEntity::getStatus, "DUPLICATE")
                        .set(UploadRecordEntity::getStatus, "SUCCEEDED")
                        .set(UploadRecordEntity::getErrorCode, "")
                        .set(UploadRecordEntity::getFinishedAt, KnowledgeValues.now()));
    }

    /** 恢复配置快照，不从当前界面重新读取解析参数。 */
    private ImportOptions decode(String value) {
        try {
            return json.readValue(value, ImportOptions.class);
        } catch (Exception exception) {
            throw invalid("INVALID_IMPORT_OPTIONS");
        }
    }

    /** Excel 采用关系查询而非正文向量化。 */
    private boolean isExcel(DocumentVersionEntity version) {
        return Set.of("XLS", "XLSX").contains(version.getFileType());
    }

    /** 公开可定位的参数错误。 */
    private ResponseStatusException invalid(String code) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, code);
    }
}
