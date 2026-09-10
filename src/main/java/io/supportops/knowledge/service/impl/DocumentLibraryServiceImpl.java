package io.supportops.knowledge.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.supportops.knowledge.constant.KnowledgeLimits;
import io.supportops.knowledge.dto.ImportOptions;
import io.supportops.knowledge.entity.DocumentChunkEntity;
import io.supportops.knowledge.entity.DocumentReleaseEntity;
import io.supportops.knowledge.entity.DocumentVersionEntity;
import io.supportops.knowledge.entity.IndexTaskEntity;
import io.supportops.knowledge.entity.KnowledgeDocumentEntity;
import io.supportops.knowledge.entity.ProcessingBatchEntity;
import io.supportops.knowledge.entity.SourceFileEntity;
import io.supportops.knowledge.entity.UploadRecordEntity;
import io.supportops.knowledge.mapper.DocumentChunkMapper;
import io.supportops.knowledge.mapper.DocumentReleaseMapper;
import io.supportops.knowledge.mapper.DocumentVersionMapper;
import io.supportops.knowledge.mapper.IndexTaskMapper;
import io.supportops.knowledge.mapper.KnowledgeDocumentMapper;
import io.supportops.knowledge.mapper.ProcessingBatchMapper;
import io.supportops.knowledge.mapper.SourceFileMapper;
import io.supportops.knowledge.mapper.UploadRecordMapper;
import io.supportops.knowledge.service.DocumentImportService;
import io.supportops.knowledge.service.DocumentIndexService;
import io.supportops.knowledge.service.DocumentLibraryService;
import io.supportops.knowledge.service.DocumentRevisionService;
import io.supportops.knowledge.service.ElasticKnowledgeIndex;
import io.supportops.knowledge.service.EmbeddingClient;
import io.supportops.knowledge.service.ExcelDataService;
import io.supportops.knowledge.service.KnowledgeSubmittedEvent;
import io.supportops.knowledge.service.KnowledgeTaskService;
import io.supportops.knowledge.service.OriginalFileStore;
import io.supportops.knowledge.service.support.KnowledgeValues;
import io.supportops.knowledge.vo.DocumentChunk;
import io.supportops.knowledge.vo.KnowledgeDocument;
import io.supportops.knowledge.vo.KnowledgeViews;
import io.supportops.user.CurrentUser;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** 文档库门面：受理上传、管理历史和清理任务，将解析与索引分别委派给阶段服务。 */
@Service
public class DocumentLibraryServiceImpl implements DocumentLibraryService {
    private final KnowledgeDocumentMapper documents;
    private final SourceFileMapper files;
    private final UploadRecordMapper uploads;
    private final DocumentVersionMapper versions;
    private final ProcessingBatchMapper batches;
    private final DocumentChunkMapper chunks;
    private final DocumentReleaseMapper releases;
    private final IndexTaskMapper taskRows;
    private final KnowledgeTaskService tasks;
    private final OriginalFileStore originals;
    private final EmbeddingClient embedding;
    private final ElasticKnowledgeIndex elastic;
    private final ExcelDataService excel;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final DocumentRevisionService revisions;
    private final DocumentImportService imports;
    private final DocumentIndexService indexing;
    private final ApplicationEventPublisher events;

    /** 结构化摘要保留字段边界，标题或说明中的分隔符不会造成幂等载荷混淆。 */
    private record UploadFingerprint(
            String filename,
            String checksum,
            String title,
            String productVersion,
            String documentId,
            String note,
            ImportOptions options) {}

    /** 注入同一业务模块的持久化与外部协议边界。 */
    public DocumentLibraryServiceImpl(
            KnowledgeDocumentMapper documents,
            SourceFileMapper files,
            UploadRecordMapper uploads,
            DocumentVersionMapper versions,
            ProcessingBatchMapper batches,
            DocumentChunkMapper chunks,
            DocumentReleaseMapper releases,
            IndexTaskMapper taskRows,
            KnowledgeTaskService tasks,
            OriginalFileStore originals,
            EmbeddingClient embedding,
            ElasticKnowledgeIndex elastic,
            ExcelDataService excel,
            TransactionTemplate transactions,
            ObjectMapper json,
            DocumentRevisionService revisions,
            DocumentImportService imports,
            DocumentIndexService indexing,
            ApplicationEventPublisher events) {
        this.documents = documents;
        this.files = files;
        this.uploads = uploads;
        this.versions = versions;
        this.batches = batches;
        this.chunks = chunks;
        this.releases = releases;
        this.taskRows = taskRows;
        this.tasks = tasks;
        this.originals = originals;
        this.embedding = embedding;
        this.elastic = elastic;
        this.excel = excel;
        this.transactions = transactions;
        this.json = json;
        this.revisions = revisions;
        this.imports = imports;
        this.indexing = indexing;
        this.events = events;
    }

    /** 先登记上传事实，再保存原件，最后原子受理修订和处理任务。 */
    @Override
    public KnowledgeViews.Upload submit(
            String filename,
            String title,
            String productVersion,
            String documentId,
            String note,
            String requestKey,
            String source,
            byte[] bytes,
            ImportOptions options) {
        String name = filename == null ? "document.md" : filename.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        if (requestKey == null || requestKey.isBlank()) {
            requestKey = KnowledgeValues.id();
        }
        if (requestKey.length() > 100 || name.length() > 200 || bytes == null) {
            throw invalid("INVALID_DOCUMENT");
        }
        UploadFingerprint fingerprint =
                new UploadFingerprint(
                        name,
                        KnowledgeValues.hash(bytes),
                        title,
                        productVersion,
                        documentId,
                        note,
                        options);
        UploadRecordEntity upload = newUpload(fingerprint, requestKey, source, bytes.length);
        UploadRecordEntity existing = registerUpload(upload);
        if (existing != null) {
            return uploadView(existing);
        }
        try {
            validateUpload(fingerprint, bytes.length);
            String type = fileType(name);
            KnowledgeDocumentEntity document =
                    findOrCreateDocument(
                            documentId, title, productVersion, name, fingerprint.checksum());
            upload.setDocumentId(document.getId());
            uploads.updateById(upload);
            DocumentVersionEntity duplicate =
                    duplicateVersion(
                            document.getId(), productVersion, fingerprint.checksum(), title);
            if (reuseProcessedUpload(upload, duplicate, options)) {
                return uploadView(upload);
            }
            // MinIO 网络写入不持有数据库事务；原件状态保留给中断恢复与孤儿文件清理。
            SourceFileEntity stored = storeUploadOriginal(upload, document, duplicate, type, bytes);
            acceptUpload(upload, document, stored, fingerprint, type);
            // 通知只用于降低延迟，恢复依据是已提交的 IMPORT 任务，通知丢失也可定时领取。
            events.publishEvent(new KnowledgeSubmittedEvent(upload.getBatchId()));
            return uploadView(upload);
        } catch (RuntimeException exception) {
            upload.setStatus("FAILED");
            upload.setErrorCode(KnowledgeValues.error(exception));
            upload.setFinishedAt(KnowledgeValues.now());
            uploads.updateById(upload);
            throw exception;
        }
    }

    /** 结构化摘要覆盖全部导入参数；上传归属取自当前用户而非请求字段。 */
    private UploadRecordEntity newUpload(
            UploadFingerprint fingerprint, String requestKey, String source, int size) {
        UploadRecordEntity upload = new UploadRecordEntity();
        upload.setUserId(CurrentUser.id());
        upload.setId(KnowledgeValues.id());
        upload.setRequestKey(requestKey);
        upload.setRequestHash(KnowledgeValues.hash(json.valueToTree(fingerprint).toString()));
        upload.setFilename(fingerprint.filename());
        upload.setSizeBytes((long) size);
        upload.setChecksum(fingerprint.checksum());
        upload.setSource(source);
        upload.setStatus("RECEIVING");
        upload.setErrorCode("");
        upload.setCreatedAt(KnowledgeValues.now());
        return upload;
    }

    /** 依靠唯一键受理上传；同用户同载荷重试复用原记录，其他冲突返回 409。 */
    private UploadRecordEntity registerUpload(UploadRecordEntity upload) {
        try {
            uploads.insert(upload);
            return null;
        } catch (DuplicateKeyException exception) {
            UploadRecordEntity existing =
                    uploads.selectOne(
                            Wrappers.<UploadRecordEntity>lambdaQuery()
                                    .eq(UploadRecordEntity::getRequestKey, upload.getRequestKey()));
            if (existing == null
                    || !Objects.equals(existing.getUserId(), CurrentUser.id())
                    || !existing.getRequestHash().equals(upload.getRequestHash())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "UPLOAD_KEY_CONFLICT");
            }
            return existing;
        }
    }

    /** 业务校验在记录创建后执行，被业务拒绝的上传仍保留失败原因。 */
    private void validateUpload(UploadFingerprint fingerprint, int size) {
        if (size > KnowledgeLimits.FILE_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE");
        }
        boolean invalidTitle =
                fingerprint.title() == null
                        || fingerprint.title().isBlank()
                        || fingerprint.title().length() > 180;
        boolean invalidVersion =
                fingerprint.productVersion() == null
                        || !fingerprint.productVersion().matches(KnowledgeLimits.VERSION_PATTERN);
        boolean invalidNote = fingerprint.note() != null && fingerprint.note().length() > 1000;
        if (size == 0 || invalidTitle || invalidVersion || invalidNote) {
            throw invalid("INVALID_DOCUMENT");
        }
    }

    /** 相同原件和参数已有可复用批次时，保存本次重复上传事实而不重复处理。 */
    private boolean reuseProcessedUpload(
            UploadRecordEntity upload, DocumentVersionEntity duplicate, ImportOptions options) {
        if (duplicate == null) {
            return false;
        }
        ProcessingBatchEntity batch = findBatch(duplicate.getId(), options);
        if (batch == null || Set.of("FAILED", "CANCELLED").contains(batch.getStatus())) {
            return false;
        }
        upload.setVersionId(duplicate.getId());
        upload.setFileId(duplicate.getFileId());
        upload.setBatchId(batch.getId());
        upload.setStatus("DUPLICATE");
        upload.setFinishedAt(KnowledgeValues.now());
        uploads.updateById(upload);
        return true;
    }

    /** 原件使用独立对象键；先登记 UPLOADING，再写 MinIO，成功后标记 STORED。 */
    private SourceFileEntity storeUploadOriginal(
            UploadRecordEntity upload,
            KnowledgeDocumentEntity document,
            DocumentVersionEntity duplicate,
            String type,
            byte[] bytes) {
        if (duplicate != null) {
            return files.selectById(duplicate.getFileId());
        }
        SourceFileEntity file = new SourceFileEntity();
        file.setId(KnowledgeValues.id());
        file.setFilename(upload.getFilename());
        file.setObjectKey("originals/" + file.getId());
        file.setDocumentId(document.getId());
        file.setMediaType(mediaType(type));
        file.setSizeBytes((long) bytes.length);
        file.setChecksum(upload.getChecksum());
        file.setStatus("UPLOADING");
        file.setCreatedAt(KnowledgeValues.now());
        files.insert(file);
        upload.setFileId(file.getId());
        uploads.updateById(upload);
        originals.put(file.getObjectKey(), bytes, file.getMediaType());
        file.setStatus("STORED");
        files.updateById(file);
        return file;
    }

    /** 锁定文档后再次去重，修订、批次和 IMPORT 任务与上传关联共同提交。 */
    private void acceptUpload(
            UploadRecordEntity upload,
            KnowledgeDocumentEntity document,
            SourceFileEntity stored,
            UploadFingerprint fingerprint,
            String type) {
        transactions.executeWithoutResult(
                transaction -> {
                    revisions.requireDocument(document.getId(), true);
                    if (Instant.parse(upload.getCreatedAt())
                            .isBefore(Instant.now().minusSeconds(300))) {
                        throw new IllegalStateException("UPLOAD_EXPIRED");
                    }
                    // 原件写入期间可能有另一请求完成同一修订，因此不能复用事务外的去重判断。
                    DocumentVersionEntity current =
                            duplicateVersion(
                                    document.getId(),
                                    fingerprint.productVersion(),
                                    fingerprint.checksum(),
                                    fingerprint.title());
                    DocumentVersionEntity version =
                            current != null
                                    ? current
                                    : createVersion(
                                            document,
                                            fingerprint.title(),
                                            fingerprint.productVersion(),
                                            stored,
                                            type,
                                            fingerprint.note());
                    ProcessingBatchEntity batch = findBatch(version.getId(), fingerprint.options());
                    boolean reuse =
                            batch != null
                                    && !Set.of("FAILED", "CANCELLED").contains(batch.getStatus());
                    if (!reuse) {
                        batch = createBatch(version, fingerprint.options());
                    }
                    upload.setFileId(version.getFileId());
                    upload.setVersionId(version.getId());
                    upload.setBatchId(batch.getId());
                    upload.setStatus(reuse ? "DUPLICATE" : "ACCEPTED");
                    if (reuse) {
                        upload.setFinishedAt(KnowledgeValues.now());
                    }
                    uploads.updateById(upload);
                });
    }

    /** 新上传可按内容和适用版本去重，更新修订必须绑定明确文档 ID。 */
    private KnowledgeDocumentEntity findOrCreateDocument(
            String id, String title, String version, String name, String checksum) {
        if (id != null && !id.isBlank()) {
            return revisions.requireDocument(id, false);
        }
        KnowledgeDocumentEntity existing =
                documents.selectOne(
                        Wrappers.<KnowledgeDocumentEntity>lambdaQuery()
                                .eq(KnowledgeDocumentEntity::getChecksum, checksum)
                                .eq(KnowledgeDocumentEntity::getVersion, version)
                                .eq(KnowledgeDocumentEntity::getDeleted, false)
                                .last("LIMIT 1"));
        if (existing != null) {
            return existing;
        }
        KnowledgeDocumentEntity document =
                new KnowledgeDocumentEntity(
                        KnowledgeValues.id(),
                        title.trim(),
                        version,
                        name,
                        checksum,
                        KnowledgeValues.now(),
                        "");
        document.setDeleted(false);
        document.setCanonicalKey(KnowledgeValues.hash(checksum + "|" + version));
        try {
            documents.insert(document);
            return document;
        } catch (DuplicateKeyException exception) {
            KnowledgeDocumentEntity concurrent =
                    documents.selectOne(
                            Wrappers.<KnowledgeDocumentEntity>lambdaQuery()
                                    .eq(
                                            KnowledgeDocumentEntity::getCanonicalKey,
                                            document.getCanonicalKey()));
            if (concurrent == null) {
                throw exception;
            }
            return concurrent;
        }
    }

    /** 同一文档、产品范围及内容复用修订；标题变化形成新的修订。 */
    private DocumentVersionEntity duplicateVersion(
            String documentId, String version, String checksum, String title) {
        for (DocumentVersionEntity item :
                versions.selectList(
                        Wrappers.<DocumentVersionEntity>lambdaQuery()
                                .eq(DocumentVersionEntity::getDocumentId, documentId)
                                .eq(DocumentVersionEntity::getProductVersion, version)
                                .eq(DocumentVersionEntity::getTitle, title.trim())
                                .orderByDesc(DocumentVersionEntity::getRevision))) {
            SourceFileEntity file = files.selectById(item.getFileId());
            if (file != null
                    && file.getChecksum().equals(checksum)
                    && file.getStatus().equals("STORED")) {
                return item;
            }
        }
        return null;
    }

    /** 在文档锁内分配修订号，历史修订不可覆盖。 */
    private DocumentVersionEntity createVersion(
            KnowledgeDocumentEntity document,
            String title,
            String productVersion,
            SourceFileEntity file,
            String type,
            String note) {
        DocumentVersionEntity latest =
                versions.selectOne(
                        Wrappers.<DocumentVersionEntity>lambdaQuery()
                                .eq(DocumentVersionEntity::getDocumentId, document.getId())
                                .orderByDesc(DocumentVersionEntity::getRevision)
                                .last("LIMIT 1"));
        DocumentVersionEntity version = new DocumentVersionEntity();
        version.setId(KnowledgeValues.id());
        version.setDocumentId(document.getId());
        version.setRevision(latest == null ? 1 : latest.getRevision() + 1);
        version.setProductVersion(productVersion);
        version.setTitle(title.trim());
        version.setFileId(file.getId());
        version.setFileType(type);
        version.setNote(note == null ? "" : note);
        version.setCreatedAt(KnowledgeValues.now());
        versions.insert(version);
        return version;
    }

    /** 批次与 IMPORT 任务共同提交，即使即时通知丢失仍可补偿。 */
    private ProcessingBatchEntity createBatch(
            DocumentVersionEntity version, ImportOptions options) {
        ProcessingBatchEntity previous =
                batches.selectOne(
                        Wrappers.<ProcessingBatchEntity>lambdaQuery()
                                .eq(ProcessingBatchEntity::getVersionId, version.getId())
                                .orderByDesc(ProcessingBatchEntity::getGeneration)
                                .last("LIMIT 1"));
        ProcessingBatchEntity batch = new ProcessingBatchEntity();
        batch.setId(KnowledgeValues.id());
        batch.setDocumentId(version.getDocumentId());
        batch.setVersionId(version.getId());
        batch.setGeneration(previous == null ? 1 : previous.getGeneration() + 1);
        batch.setChunkSize(options.chunkSize());
        batch.setOverlap(options.overlap());
        batch.setConfigJson(encode(options));
        batch.setConfigHash(KnowledgeValues.hash(encode(options)));
        batch.setParserVersion("supportops-parser-2");
        batch.setStatus("PENDING");
        boolean tabular = isExcel(version);
        batch.setTextStatus(tabular ? "NOT_APPLICABLE" : "PENDING");
        batch.setVectorStatus(tabular ? "NOT_APPLICABLE" : "PENDING");
        batch.setChunkCount(0);
        batch.setRowCount(0L);
        batch.setErrorCode("");
        batch.setCreatedAt(KnowledgeValues.now());
        batches.insert(batch);
        tasks.create(batch, "IMPORT", "");
        return batch;
    }

    /** 相同解析参数定位可复用处理结果。 */
    private ProcessingBatchEntity findBatch(String versionId, ImportOptions options) {
        return batches.selectOne(
                Wrappers.<ProcessingBatchEntity>lambdaQuery()
                        .eq(ProcessingBatchEntity::getVersionId, versionId)
                        .eq(
                                ProcessingBatchEntity::getConfigHash,
                                KnowledgeValues.hash(encode(options)))
                        .orderByDesc(ProcessingBatchEntity::getGeneration)
                        .last("LIMIT 1"));
    }

    /** 工作线程从持久化任务恢复执行，不依赖上传请求线程中的对象。 */
    @Override
    public void execute(IndexTaskEntity task) {
        if (task.getKind().equals("FILE_CLEANUP")) {
            cleanupFile(task);
            return;
        }
        if (task.getKind().equals("CLEANUP")) {
            cleanup(task);
            return;
        }
        revisions.requireDocument(task.getDocumentId(), false);
        tasks.requireLease(task);
        ProcessingBatchEntity batch = batches.selectById(task.getBatchId());
        if (batch == null || batch.getStatus().equals("CANCELLED")) {
            throw new IllegalStateException("TASK_SUPERSEDED");
        }
        switch (task.getKind()) {
            case "IMPORT" -> imports.importBatch(task, batch);
            case "TEXT" -> indexing.indexText(task, batch);
            case "VECTOR" -> indexing.indexVector(task, batch);
            default -> throw new IllegalStateException("INVALID_TASK_KIND");
        }
    }

    /** 按时间倒序显示逻辑文档，历史修订在独立入口读取。 */
    @Override
    public List<KnowledgeDocument> documents() {
        return documents
                .selectList(
                        Wrappers.<KnowledgeDocumentEntity>lambdaQuery()
                                .eq(KnowledgeDocumentEntity::getDeleted, false)
                                .orderByDesc(KnowledgeDocumentEntity::getCreatedAt))
                .stream()
                .map(document -> document(document.getId()))
                .toList();
    }

    /** 兼容原字段并补充当前目标批次的状态。 */
    @Override
    public KnowledgeDocument document(String id) {
        KnowledgeDocumentEntity document = revisions.requireDocument(id, false);
        DocumentVersionEntity version =
                versions.selectOne(
                        Wrappers.<DocumentVersionEntity>lambdaQuery()
                                .eq(DocumentVersionEntity::getDocumentId, id)
                                .orderByDesc(DocumentVersionEntity::getRevision)
                                .last("LIMIT 1"));
        if (version == null) {
            return new KnowledgeDocument(
                    id,
                    document.getTitle(),
                    document.getVersion(),
                    document.getFilename(),
                    document.getCreatedAt(),
                    "",
                    0);
        }
        ProcessingBatchEntity batch =
                batches.selectOne(
                        Wrappers.<ProcessingBatchEntity>lambdaQuery()
                                .eq(ProcessingBatchEntity::getVersionId, version.getId())
                                .orderByDesc(ProcessingBatchEntity::getGeneration)
                                .last("LIMIT 1"));
        String profile = "";
        if (batch != null) {
            DocumentReleaseEntity release = revisions.release(batch);
            IndexTaskEntity vector =
                    release == null || release.getVectorTaskId() == null
                            ? null
                            : taskRows.selectById(release.getVectorTaskId());
            if (vector != null && vector.getProfileKey().equals(embedding.fingerprint())) {
                profile = vector.getProfileKey();
            }
        }
        return new KnowledgeDocument(
                id,
                version.getTitle(),
                version.getProductVersion(),
                files.selectById(version.getFileId()).getFilename(),
                document.getCreatedAt(),
                profile,
                batch == null ? 0 : batch.getChunkCount(),
                version.getId(),
                batch == null ? null : batch.getId(),
                batch == null ? "PENDING" : batch.getStatus(),
                batch == null ? "PENDING" : batch.getTextStatus(),
                batch == null ? "PENDING" : batch.getVectorStatus());
    }

    /** 内容使用数字序号，引用批次不会因新版本发布而改变。 */
    @Override
    public List<DocumentChunk> content(String id, String batchId) {
        revisions.requireDocument(id, false);
        String selected = batchId == null ? document(id).batchId() : batchId;
        return chunks
                .selectList(
                        Wrappers.<DocumentChunkEntity>lambdaQuery()
                                .eq(DocumentChunkEntity::getDocumentId, id)
                                .eq(selected != null, DocumentChunkEntity::getBatchId, selected)
                                .orderByAsc(DocumentChunkEntity::getChunkIndex))
                .stream()
                .map(item -> new DocumentChunk(item.getId(), item.getLocation(), item.getContent()))
                .toList();
    }

    /** 逻辑删除立即阻止检索，外部清理由持久化任务完成。 */
    @Override
    public void delete(String id) {
        transactions.executeWithoutResult(
                transaction -> {
                    revisions.requireDocument(id, true);
                    documents.update(
                            Wrappers.<KnowledgeDocumentEntity>lambdaUpdate()
                                    .eq(KnowledgeDocumentEntity::getId, id)
                                    .set(KnowledgeDocumentEntity::getDeleted, true)
                                    .set(KnowledgeDocumentEntity::getCanonicalKey, null));
                    long lastLease =
                            taskRows
                                    .selectList(
                                            Wrappers.<IndexTaskEntity>lambdaQuery()
                                                    .eq(IndexTaskEntity::getDocumentId, id))
                                    .stream()
                                    .mapToLong(IndexTaskEntity::getLeaseUntil)
                                    .max()
                                    .orElse(0);
                    tasks.cancel(id, null);
                    batches.update(
                            Wrappers.<ProcessingBatchEntity>lambdaUpdate()
                                    .eq(ProcessingBatchEntity::getDocumentId, id)
                                    .set(ProcessingBatchEntity::getStatus, "CANCELLED"));
                    releases.delete(
                            Wrappers.<DocumentReleaseEntity>lambdaQuery()
                                    .eq(DocumentReleaseEntity::getDocumentId, id));
                    ProcessingBatchEntity batch = new ProcessingBatchEntity();
                    batch.setId(id);
                    batch.setDocumentId(id);
                    batch.setVersionId(id);
                    IndexTaskEntity cleanup = tasks.create(batch, "CLEANUP", "");
                    cleanup.setNextRetryAt(Math.max(System.currentTimeMillis(), lastLease) + 60000);
                    taskRows.updateById(cleanup);
                });
        events.publishEvent(new KnowledgeSubmittedEvent(id));
    }

    /** 清理所有已登记对象，失败可重复执行；原记录留存。 */
    private void cleanup(IndexTaskEntity task) {
        tasks.requireLease(task);
        elastic.deleteDocument(task.getDocumentId());
        excel.delete(task.getDocumentId());
        for (DocumentVersionEntity version :
                versions.selectList(
                        Wrappers.<DocumentVersionEntity>lambdaQuery()
                                .eq(DocumentVersionEntity::getDocumentId, task.getDocumentId()))) {
            SourceFileEntity file = files.selectById(version.getFileId());
            if (!file.getStatus().equals("DELETED") && !file.getStatus().equals("MISSING")) {
                originals.delete(file.getObjectKey());
                file.setStatus("DELETED");
                files.updateById(file);
            }
        }
    }

    /** 重建使用独立任务代，原有效向量在新结果发布前不受影响。 */
    @Override
    public void reindex(String id) {
        KnowledgeDocument document = document(id);
        if (document.batchId() == null) {
            throw invalid("DOCUMENT_NOT_PROCESSED");
        }
        ProcessingBatchEntity batch = batches.selectById(document.batchId());
        if (!batch.getStatus().equals("READY") || !batch.getTextStatus().equals("SUCCEEDED")) {
            throw invalid("DOCUMENT_NOT_SEARCHABLE");
        }
        if (isExcel(versions.selectById(batch.getVersionId()))) {
            throw invalid("EXCEL_USES_SQL");
        }
        embedding.snapshot().requireConfigured();
        transactions.executeWithoutResult(
                transaction -> {
                    revisions.requireDocument(id, true);
                    tasks.cancel(id, batch.getId());
                    tasks.create(batch, "VECTOR", embedding.fingerprint());
                    batches.update(
                            Wrappers.<ProcessingBatchEntity>lambdaUpdate()
                                    .eq(ProcessingBatchEntity::getId, batch.getId())
                                    .set(ProcessingBatchEntity::getVectorStatus, "PENDING"));
                });
        events.publishEvent(new KnowledgeSubmittedEvent(batch.getId()));
    }

    /** 配置补齐后自动重排等待任务，不反复调用错误凭据。 */
    @Override
    public void wakeConfiguredTasks() {
        recoverUploads();
        if (!embedding.configured()) {
            return;
        }
        for (IndexTaskEntity task :
                taskRows.selectList(
                        Wrappers.<IndexTaskEntity>lambdaQuery()
                                .eq(IndexTaskEntity::getKind, "VECTOR")
                                .eq(IndexTaskEntity::getStatus, "BLOCKED")
                                .eq(IndexTaskEntity::getErrorCode, "EMBEDDING_NOT_CONFIGURED"))) {
            taskRows.update(
                    Wrappers.<IndexTaskEntity>lambdaUpdate()
                            .eq(IndexTaskEntity::getId, task.getId())
                            .eq(IndexTaskEntity::getStatus, "BLOCKED")
                            .set(IndexTaskEntity::getProfileKey, embedding.fingerprint())
                            .set(IndexTaskEntity::getStatus, "PENDING")
                            .set(IndexTaskEntity::getNextRetryAt, 0L));
        }
    }

    /** 过期受理记录明确失败，并为未被修订引用的原件登记可补偿清理任务。 */
    private void recoverUploads() {
        String cutoff = Instant.now().minusSeconds(3600).toString();
        uploads.update(
                Wrappers.<UploadRecordEntity>lambdaUpdate()
                        .eq(UploadRecordEntity::getStatus, "RECEIVING")
                        .lt(UploadRecordEntity::getCreatedAt, cutoff)
                        .set(UploadRecordEntity::getStatus, "FAILED")
                        .set(UploadRecordEntity::getErrorCode, "UPLOAD_INTERRUPTED")
                        .set(UploadRecordEntity::getFinishedAt, KnowledgeValues.now()));
        for (SourceFileEntity file :
                files.selectList(
                        Wrappers.<SourceFileEntity>lambdaQuery()
                                .in(SourceFileEntity::getStatus, List.of("UPLOADING", "STORED"))
                                .lt(SourceFileEntity::getCreatedAt, cutoff)
                                .isNotNull(SourceFileEntity::getDocumentId))) {
            if (versions.selectCount(
                                    Wrappers.<DocumentVersionEntity>lambdaQuery()
                                            .eq(DocumentVersionEntity::getFileId, file.getId()))
                            != 0
                    || taskRows.selectCount(
                                    Wrappers.<IndexTaskEntity>lambdaQuery()
                                            .eq(IndexTaskEntity::getKind, "FILE_CLEANUP")
                                            .eq(IndexTaskEntity::getVersionId, file.getId()))
                            != 0) {
                continue;
            }
            ProcessingBatchEntity owner = new ProcessingBatchEntity();
            owner.setId(file.getId());
            owner.setVersionId(file.getId());
            owner.setDocumentId(file.getDocumentId());
            tasks.create(owner, "FILE_CLEANUP", "");
        }
    }

    /** 迟到上传曾经写出的对象只有在确定未被任何修订引用时才可回收。 */
    private void cleanupFile(IndexTaskEntity task) {
        tasks.requireLease(task);
        SourceFileEntity file = files.selectById(task.getVersionId());
        if (file == null
                || versions.selectCount(
                                Wrappers.<DocumentVersionEntity>lambdaQuery()
                                        .eq(DocumentVersionEntity::getFileId, file.getId()))
                        != 0) {
            return;
        }
        originals.delete(file.getObjectKey());
        file.setStatus("DELETED");
        files.updateById(file);
    }

    /** 返回一次上传记录。 */
    @Override
    public KnowledgeViews.Upload upload(String id) {
        UploadRecordEntity item = uploads.selectById(id);
        if (item == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "UPLOAD_NOT_FOUND");
        }
        return uploadView(item);
    }

    /** 上传历史保留重复及失败记录。 */
    @Override
    public List<KnowledgeViews.Upload> uploads(String documentId) {
        return uploads
                .selectList(
                        Wrappers.<UploadRecordEntity>lambdaQuery()
                                .eq(
                                        documentId != null,
                                        UploadRecordEntity::getDocumentId,
                                        documentId)
                                .orderByDesc(UploadRecordEntity::getCreatedAt)
                                .last("LIMIT 200"))
                .stream()
                .map(this::uploadView)
                .toList();
    }

    /** 修订历史不依据文件名合并。 */
    @Override
    public List<KnowledgeViews.Version> versions(String documentId) {
        return versions
                .selectList(
                        Wrappers.<DocumentVersionEntity>lambdaQuery()
                                .eq(DocumentVersionEntity::getDocumentId, documentId)
                                .orderByDesc(DocumentVersionEntity::getRevision))
                .stream()
                .map(
                        item ->
                                new KnowledgeViews.Version(
                                        item.getId(),
                                        item.getDocumentId(),
                                        item.getRevision(),
                                        item.getProductVersion(),
                                        item.getTitle(),
                                        item.getFileId(),
                                        item.getFileType(),
                                        item.getNote(),
                                        item.getCreatedAt(),
                                        files.selectById(item.getFileId()).getStatus()))
                .toList();
    }

    /** 返回解析、关键词及向量状态。 */
    @Override
    public List<KnowledgeViews.Batch> batches(String documentId) {
        return batches
                .selectList(
                        Wrappers.<ProcessingBatchEntity>lambdaQuery()
                                .eq(ProcessingBatchEntity::getDocumentId, documentId)
                                .orderByDesc(ProcessingBatchEntity::getCreatedAt))
                .stream()
                .map(this::batchView)
                .toList();
    }

    /** 使用已保存原件生成新的批次，不覆盖任何已存在的分片。 */
    @Override
    public KnowledgeViews.Batch reprocess(String versionId, ImportOptions options) {
        DocumentVersionEntity version = versions.selectById(versionId);
        if (version == null) {
            throw invalid("VERSION_NOT_FOUND");
        }
        ProcessingBatchEntity batch =
                transactions.execute(
                        transaction -> {
                            revisions.requireDocument(version.getDocumentId(), true);
                            return createBatch(version, options);
                        });
        events.publishEvent(new KnowledgeSubmittedEvent(batch.getId()));
        return batchView(batch);
    }

    /** 通过修订服务读取私有原件；返回流仍由调用方关闭。 */
    @Override
    public InputStream original(String versionId) {
        return revisions.original(versionId);
    }

    /** 返回已核对版本归属的原文件名。 */
    @Override
    public String originalFilename(String versionId) {
        DocumentVersionEntity version = versions.selectById(versionId);
        if (version == null) {
            throw invalid("VERSION_NOT_FOUND");
        }
        revisions.requireDocument(version.getDocumentId(), false);
        return files.selectById(version.getFileId()).getFilename();
    }

    /** 补传只恢复同一个摘要的缺失原件，不能借此改写历史引用。 */
    @Override
    public void restoreOriginal(String versionId, byte[] bytes) {
        DocumentVersionEntity version = versions.selectById(versionId);
        if (version == null) {
            throw invalid("VERSION_NOT_FOUND");
        }
        revisions.requireDocument(version.getDocumentId(), false);
        SourceFileEntity file = files.selectById(version.getFileId());
        UploadRecordEntity upload = new UploadRecordEntity();
        upload.setUserId(CurrentUser.id());
        upload.setId(KnowledgeValues.id());
        upload.setRequestKey(KnowledgeValues.id());
        upload.setRequestHash(KnowledgeValues.hash(bytes));
        upload.setChecksum(KnowledgeValues.hash(bytes));
        upload.setDocumentId(version.getDocumentId());
        upload.setVersionId(versionId);
        upload.setFileId(file.getId());
        upload.setFilename(file.getFilename());
        upload.setSizeBytes((long) bytes.length);
        upload.setSource("RESTORE");
        upload.setStatus("RECEIVING");
        upload.setErrorCode("");
        upload.setCreatedAt(KnowledgeValues.now());
        uploads.insert(upload);
        try {
            if (bytes.length == 0
                    || bytes.length > KnowledgeLimits.FILE_BYTES
                    || !KnowledgeValues.hash(bytes).equals(file.getChecksum())) {
                throw invalid("ORIGINAL_CHECKSUM_MISMATCH");
            }
            if (!file.getStatus().equals("MISSING")) {
                throw invalid("ORIGINAL_ALREADY_PRESENT");
            }
            originals.put(file.getObjectKey(), bytes, mediaType(version.getFileType()));
            transactions.executeWithoutResult(
                    transaction -> {
                        revisions.requireDocument(version.getDocumentId(), true);
                        int changed =
                                files.update(
                                        Wrappers.<SourceFileEntity>lambdaUpdate()
                                                .eq(SourceFileEntity::getId, file.getId())
                                                .eq(SourceFileEntity::getStatus, "MISSING")
                                                .set(SourceFileEntity::getStatus, "STORED")
                                                .set(
                                                        SourceFileEntity::getSizeBytes,
                                                        (long) bytes.length)
                                                .set(
                                                        SourceFileEntity::getMediaType,
                                                        mediaType(version.getFileType())));
                        if (changed != 1) {
                            throw invalid("ORIGINAL_ALREADY_PRESENT");
                        }
                        upload.setStatus("SUCCEEDED");
                        upload.setFinishedAt(KnowledgeValues.now());
                        uploads.updateById(upload);
                    });
        } catch (RuntimeException exception) {
            upload.setStatus("FAILED");
            upload.setErrorCode(KnowledgeValues.error(exception));
            upload.setFinishedAt(KnowledgeValues.now());
            uploads.updateById(upload);
            throw exception;
        }
    }

    /** 固定上传响应投影。 */
    private KnowledgeViews.Upload uploadView(UploadRecordEntity item) {
        return new KnowledgeViews.Upload(
                item.getId(),
                item.getDocumentId(),
                item.getVersionId(),
                item.getBatchId(),
                item.getFilename(),
                item.getSizeBytes(),
                item.getStatus(),
                item.getErrorCode(),
                item.getCreatedAt(),
                item.getUserId());
    }

    /** 固定处理批次响应投影。 */
    private KnowledgeViews.Batch batchView(ProcessingBatchEntity item) {
        // H2 旧表未记录分片参数，迁移占位值不能作为实际执行配置展示给用户。
        boolean parametersKnown = !"legacy-h2".equals(item.getParserVersion());
        return new KnowledgeViews.Batch(
                item.getId(),
                item.getVersionId(),
                parametersKnown ? item.getChunkSize() : null,
                parametersKnown ? item.getOverlap() : null,
                item.getStatus(),
                item.getTextStatus(),
                item.getVectorStatus(),
                item.getChunkCount(),
                item.getRowCount(),
                item.getErrorCode(),
                item.getCreatedAt());
    }

    /** 文件类型来自受限扩展名，解析器仍检查实际结构。 */
    private String fileType(String filename) {
        int dot = filename.lastIndexOf('.');
        String type = dot < 0 ? "" : filename.substring(dot + 1).toUpperCase(Locale.ROOT);
        if (!Set.of("MD", "PDF", "XLS", "XLSX").contains(type)) {
            throw new ResponseStatusException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE, "ONLY_MD_PDF_XLS_XLSX_SUPPORTED");
        }
        return type;
    }

    /** Excel 采用关系查询而非正文向量化。 */
    private boolean isExcel(DocumentVersionEntity version) {
        return Set.of("XLS", "XLSX").contains(version.getFileType());
    }

    /** 统一原文件媒体类型。 */
    private String mediaType(String type) {
        return switch (type) {
            case "PDF" -> "application/pdf";
            case "MD" -> "text/markdown; charset=utf-8";
            case "XLS" -> "application/vnd.ms-excel";
            default -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        };
    }

    /** 固定配置对象编码为任务快照。 */
    private String encode(ImportOptions options) {
        try {
            return json.writeValueAsString(options);
        } catch (Exception exception) {
            throw invalid("INVALID_IMPORT_OPTIONS");
        }
    }

    /** 公开可定位的参数错误。 */
    private ResponseStatusException invalid(String code) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, code);
    }
}
