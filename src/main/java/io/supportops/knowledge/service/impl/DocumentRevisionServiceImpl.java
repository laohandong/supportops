package io.supportops.knowledge.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import io.supportops.knowledge.entity.DocumentReleaseEntity;
import io.supportops.knowledge.entity.DocumentVersionEntity;
import io.supportops.knowledge.entity.KnowledgeDocumentEntity;
import io.supportops.knowledge.entity.ProcessingBatchEntity;
import io.supportops.knowledge.entity.SourceFileEntity;
import io.supportops.knowledge.mapper.DocumentReleaseMapper;
import io.supportops.knowledge.mapper.DocumentVersionMapper;
import io.supportops.knowledge.mapper.KnowledgeCatalogMapper;
import io.supportops.knowledge.mapper.KnowledgeDocumentMapper;
import io.supportops.knowledge.mapper.ProcessingBatchMapper;
import io.supportops.knowledge.mapper.SourceFileMapper;
import io.supportops.knowledge.service.DocumentRevisionService;
import io.supportops.knowledge.service.OriginalFileStore;
import io.supportops.knowledge.service.support.KnowledgeValues;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.InputStream;

/** 管理未删除文档、原件读取及单调前进的发布指针；事务由调用方统一组织。 */
@Service
public class DocumentRevisionServiceImpl implements DocumentRevisionService {
    private final KnowledgeDocumentMapper documents;
    private final SourceFileMapper files;
    private final DocumentVersionMapper versions;
    private final ProcessingBatchMapper batches;
    private final DocumentReleaseMapper releases;
    private final KnowledgeCatalogMapper catalog;
    private final OriginalFileStore originals;

    /** 注入本阶段使用的持久化、事务及外部服务边界。 */
    public DocumentRevisionServiceImpl(
            KnowledgeDocumentMapper documents,
            SourceFileMapper files,
            DocumentVersionMapper versions,
            ProcessingBatchMapper batches,
            DocumentReleaseMapper releases,
            KnowledgeCatalogMapper catalog,
            OriginalFileStore originals) {
        this.documents = documents;
        this.files = files;
        this.versions = versions;
        this.batches = batches;
        this.releases = releases;
        this.catalog = catalog;
        this.originals = originals;
    }

    /** 读取未删除的逻辑文档，按需在事务内持有行锁。 */
    @Override
    public KnowledgeDocumentEntity requireDocument(String id, boolean lock) {
        KnowledgeDocumentEntity document =
                lock ? catalog.lockDocument(id) : documents.selectById(id);
        if (document == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "DOCUMENT_NOT_FOUND");
        }
        if (Boolean.TRUE.equals(document.getDeleted())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "DOCUMENT_DELETED");
        }
        return document;
    }

    /** 精确修订的私有原件，只允许未删除文档读取。 */
    @Override
    public InputStream original(String versionId) {
        DocumentVersionEntity version = versions.selectById(versionId);
        if (version == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "VERSION_NOT_FOUND");
        }
        requireDocument(version.getDocumentId(), false);
        SourceFileEntity file = files.selectById(version.getFileId());
        if (file == null || !file.getStatus().equals("STORED")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ORIGINAL_FILE_MISSING");
        }
        return originals.open(file.getObjectKey());
    }

    /** 查询该批次所属产品版本的发布关系。 */
    @Override
    public DocumentReleaseEntity release(ProcessingBatchEntity batch) {
        return releases.selectOne(
                Wrappers.<DocumentReleaseEntity>lambdaQuery()
                        .eq(DocumentReleaseEntity::getDocumentId, batch.getDocumentId())
                        .eq(
                                DocumentReleaseEntity::getProductVersion,
                                versions.selectById(batch.getVersionId()).getProductVersion()));
    }

    /** 发布只向更新修订或同修订更新批次前进，旧工作线程不能回退发布。 */
    @Override
    public void publish(ProcessingBatchEntity batch, String vectorTask) {
        DocumentVersionEntity version = versions.selectById(batch.getVersionId());
        DocumentReleaseEntity release = release(batch);
        if (release != null) {
            DocumentVersionEntity current = versions.selectById(release.getVersionId());
            ProcessingBatchEntity currentBatch = batches.selectById(release.getBatchId());
            boolean newerRevisionPublished = current.getRevision() > version.getRevision();
            boolean newerBatchPublished =
                    current.getRevision().equals(version.getRevision())
                            && currentBatch.getGeneration() > batch.getGeneration();
            // 修订号优先于批次代数，迟到的旧任务即使索引完整也不能回退已生效资料。
            if (newerRevisionPublished || newerBatchPublished) {
                throw new IllegalStateException("TASK_SUPERSEDED");
            }
            if (release.getBatchId().equals(batch.getId())) {
                // 同批次重复发布无需覆盖，尤其不能清掉已经成功关联的向量任务。
                return;
            }
            // 新批次先发布文本（或 Excel）；此前批次的向量关联同时失效。
            releases.update(
                    Wrappers.<DocumentReleaseEntity>lambdaUpdate()
                            .eq(DocumentReleaseEntity::getId, release.getId())
                            .set(DocumentReleaseEntity::getVersionId, version.getId())
                            .set(DocumentReleaseEntity::getBatchId, batch.getId())
                            .set(DocumentReleaseEntity::getVectorTaskId, vectorTask)
                            .set(DocumentReleaseEntity::getUpdatedAt, KnowledgeValues.now()));
        } else {
            release = new DocumentReleaseEntity();
            release.setId(KnowledgeValues.id());
            release.setDocumentId(batch.getDocumentId());
            release.setProductVersion(version.getProductVersion());
            release.setVersionId(version.getId());
            release.setBatchId(batch.getId());
            release.setVectorTaskId(vectorTask);
            release.setUpdatedAt(KnowledgeValues.now());
            releases.insert(release);
        }
    }
}
