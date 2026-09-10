package io.supportops.knowledge.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import io.supportops.config.ProviderRegistry.Endpoint;
import io.supportops.knowledge.dto.ChunkSource;
import io.supportops.knowledge.entity.DocumentReleaseEntity;
import io.supportops.knowledge.entity.IndexTaskEntity;
import io.supportops.knowledge.entity.ProcessingBatchEntity;
import io.supportops.knowledge.mapper.DocumentReleaseMapper;
import io.supportops.knowledge.mapper.IndexTaskMapper;
import io.supportops.knowledge.mapper.KnowledgeCatalogMapper;
import io.supportops.knowledge.mapper.ProcessingBatchMapper;
import io.supportops.knowledge.service.DocumentIndexService;
import io.supportops.knowledge.service.DocumentRevisionService;
import io.supportops.knowledge.service.ElasticKnowledgeIndex;
import io.supportops.knowledge.service.EmbeddingClient;
import io.supportops.knowledge.service.KnowledgeTaskService;
import io.supportops.knowledge.service.support.KnowledgeValues;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/** 编排关键词与向量索引；外部请求在事务外执行，最终发布仍受文档锁和任务租约保护。 */
@Service
public class DocumentIndexServiceImpl implements DocumentIndexService {
    private final ProcessingBatchMapper batches;
    private final DocumentReleaseMapper releases;
    private final IndexTaskMapper taskRows;
    private final KnowledgeCatalogMapper catalog;
    private final KnowledgeTaskService tasks;
    private final EmbeddingClient embedding;
    private final ElasticKnowledgeIndex elastic;
    private final TransactionTemplate transactions;
    private final DocumentRevisionService revisions;

    /** 注入本阶段使用的持久化、事务及外部服务边界。 */
    public DocumentIndexServiceImpl(
            ProcessingBatchMapper batches,
            DocumentReleaseMapper releases,
            IndexTaskMapper taskRows,
            KnowledgeCatalogMapper catalog,
            KnowledgeTaskService tasks,
            EmbeddingClient embedding,
            ElasticKnowledgeIndex elastic,
            TransactionTemplate transactions,
            DocumentRevisionService revisions) {
        this.batches = batches;
        this.releases = releases;
        this.taskRows = taskRows;
        this.catalog = catalog;
        this.tasks = tasks;
        this.embedding = embedding;
        this.elastic = elastic;
        this.transactions = transactions;
        this.revisions = revisions;
    }

    /** 先完整写入关键词索引，再在短事务内发布并受理向量任务。 */
    @Override
    public void indexText(IndexTaskEntity task, ProcessingBatchEntity batch) {
        elastic.ensure(elastic.textIndex(), 0);
        writeTextChunks(task, batch);
        elastic.verify(elastic.textIndex(), "batchId", batch.getId(), batch.getChunkCount());
        publishTextAndScheduleVector(task, batch);
    }

    /** 按数字序号游标分页读取 MySQL 分片，重试使用相同 ES 文档 ID。 */
    private void writeTextChunks(IndexTaskEntity task, ProcessingBatchEntity batch) {
        int after = 0;
        while (true) {
            tasks.requireLease(task);
            List<ChunkSource> sources = catalog.sources(batch.getId(), after, 100);
            if (sources.isEmpty()) {
                return;
            }
            elastic.putText(sources, task.getAttemptCount());
            after = sources.getLast().chunkIndex();
            tasks.progress(task, "ES_WRITING", after);
        }
    }

    /** 关键词可独立使用；向量配置缺失只阻塞 VECTOR，不撤回文本发布结果。 */
    private void publishTextAndScheduleVector(IndexTaskEntity task, ProcessingBatchEntity batch) {
        transactions.executeWithoutResult(
                transaction -> {
                    revisions.requireDocument(batch.getDocumentId(), true);
                    tasks.requireLease(task);
                    revisions.publish(batch, null);
                    boolean vectorTaskExists =
                            taskRows.selectCount(
                                            Wrappers.<IndexTaskEntity>lambdaQuery()
                                                    .eq(IndexTaskEntity::getBatchId, batch.getId())
                                                    .eq(IndexTaskEntity::getKind, "VECTOR"))
                                    != 0;
                    if (vectorTaskExists) {
                        return;
                    }
                    IndexTaskEntity vector = tasks.create(batch, "VECTOR", embedding.fingerprint());
                    if (!embedding.configured()) {
                        vector.setStatus("BLOCKED");
                        vector.setErrorCode("EMBEDDING_NOT_CONFIGURED");
                        taskRows.updateById(vector);
                        batches.update(
                                Wrappers.<ProcessingBatchEntity>lambdaUpdate()
                                        .eq(ProcessingBatchEntity::getId, batch.getId())
                                        .set(ProcessingBatchEntity::getVectorStatus, "BLOCKED"));
                    }
                });
    }

    /** 固定模型快照，补齐向量后核对完整性；模型和 ES 请求始终在事务外。 */
    @Override
    public void indexVector(IndexTaskEntity task, ProcessingBatchEntity batch) {
        Endpoint endpoint = requireTaskEndpoint(task);
        String profile = embedding.fingerprint(endpoint);
        int completed = writeVectorChunks(task, batch, endpoint, profile);
        if (task.getIndexName().isEmpty()) {
            throw new IllegalStateException("NO_EXTRACTABLE_TEXT");
        }
        tasks.progress(task, "VERIFYING", completed);
        elastic.verify(task.getIndexName(), "taskId", task.getId(), batch.getChunkCount());
        publishVector(task, batch, profile);
    }

    /** 一次任务只使用一种向量空间，执行期间切换模型不能把两种向量写进同一代。 */
    private Endpoint requireTaskEndpoint(IndexTaskEntity task) {
        Endpoint endpoint = embedding.snapshot();
        endpoint.requireConfigured();
        String profile = embedding.fingerprint(endpoint);
        if (task.getProfileKey().isBlank()) {
            task.setProfileKey(profile);
        }
        if (!task.getProfileKey().equals(profile)) {
            throw new IllegalStateException("TASK_SUPERSEDED");
        }
        return endpoint;
    }

    /** 每次读取一页分片，文档内逐片调用模型；文档之间的并发由 KnowledgeWorker 限制。 */
    private int writeVectorChunks(
            IndexTaskEntity task, ProcessingBatchEntity batch, Endpoint endpoint, String profile) {
        int after = 0;
        while (true) {
            tasks.requireLease(task);
            List<ChunkSource> sources = catalog.sources(batch.getId(), after, 50);
            if (sources.isEmpty()) {
                return after;
            }
            for (ChunkSource source : sources) {
                tasks.requireLease(task);
                writeVectorIfMissing(task, source, endpoint, profile, after);
                after = source.chunkIndex();
                tasks.progress(task, "EMBEDDING", after);
            }
        }
    }

    /** 核对 ES 中的稳定 ID 和内容摘要，已成功的分片不重复消耗模型请求。 */
    private void writeVectorIfMissing(
            IndexTaskEntity task,
            ChunkSource source,
            Endpoint endpoint,
            String profile,
            int completed) {
        boolean alreadyWritten =
                !task.getIndexName().isEmpty()
                        && elastic.hasVector(task.getIndexName(), task.getId(), source);
        if (alreadyWritten) {
            return;
        }
        tasks.progress(task, "EMBEDDING", completed);
        double[] vector = embedding.embed(endpoint, source.content());
        if (task.getDimensions() > 0 && task.getDimensions() != vector.length) {
            throw new IllegalStateException("EMBEDDING_DIMENSIONS_CHANGED");
        }
        task.setDimensions(vector.length);
        task.setIndexName(elastic.vectorIndex(profile, vector.length));
        elastic.ensure(task.getIndexName(), vector.length);
        tasks.progress(task, "ES_WRITING", completed);
        elastic.putVector(
                task.getIndexName(), task.getId(), source, vector, task.getAttemptCount());
    }

    /** 仅绑定仍然有效的文本批次，迟到任务不能把当前发布指针切回旧批次。 */
    private void publishVector(IndexTaskEntity task, ProcessingBatchEntity batch, String profile) {
        transactions.executeWithoutResult(
                transaction -> {
                    revisions.requireDocument(batch.getDocumentId(), true);
                    tasks.requireLease(task);
                    DocumentReleaseEntity release = revisions.release(batch);
                    if (release == null
                            || !release.getBatchId().equals(batch.getId())
                            || !embedding.fingerprint().equals(profile)) {
                        throw new IllegalStateException("TASK_SUPERSEDED");
                    }
                    // ES 中部分写入的向量只是暂存结果，只有这个关联提交后才参与检索。
                    release.setVectorTaskId(task.getId());
                    release.setUpdatedAt(KnowledgeValues.now());
                    releases.updateById(release);
                });
    }
}
