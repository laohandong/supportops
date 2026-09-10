package io.supportops.knowledge.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import io.supportops.knowledge.entity.IndexTaskEntity;
import io.supportops.knowledge.entity.ProcessingBatchEntity;
import io.supportops.knowledge.entity.TaskAttemptEntity;
import io.supportops.knowledge.mapper.IndexTaskMapper;
import io.supportops.knowledge.mapper.ProcessingBatchMapper;
import io.supportops.knowledge.mapper.TaskAttemptMapper;
import io.supportops.knowledge.service.KnowledgeTaskService;
import io.supportops.knowledge.service.support.KnowledgeValues;
import io.supportops.knowledge.vo.KnowledgeViews;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/** MySQL 条件更新领取任务；网络调用完全位于这些短事务之外。 */
@Service
public class KnowledgeTaskServiceImpl implements KnowledgeTaskService {
    private final IndexTaskMapper tasks;
    private final TaskAttemptMapper attempts;
    private final ProcessingBatchMapper batches;
    private final TransactionTemplate transactions;
    private final long leaseMs;
    private final int maxAttempts;
    private final long retryBaseMs;

    /** 注入任务持久化与可配置的补偿参数。 */
    public KnowledgeTaskServiceImpl(
            IndexTaskMapper tasks,
            TaskAttemptMapper attempts,
            ProcessingBatchMapper batches,
            TransactionTemplate transactions,
            @Value("${supportops.knowledge.jobs.lease-ms:120000}") long leaseMs,
            @Value("${supportops.knowledge.jobs.max-attempts:5}") int maxAttempts,
            @Value("${supportops.knowledge.jobs.retry-base-ms:60000}") long retryBaseMs) {
        this.tasks = tasks;
        this.attempts = attempts;
        this.batches = batches;
        this.transactions = transactions;
        if (leaseMs < 1000 || maxAttempts < 1 || maxAttempts > 20 || retryBaseMs < 1) {
            throw new IllegalArgumentException("INVALID_KNOWLEDGE_JOB_CONFIGURATION");
        }
        this.leaseMs = leaseMs;
        this.maxAttempts = maxAttempts;
        this.retryBaseMs = retryBaseMs;
    }

    /** 任务行就是持久化调度意图，事务提交后扫描即可执行。 */
    @Override
    public IndexTaskEntity create(ProcessingBatchEntity batch, String kind, String profile) {
        IndexTaskEntity task = new IndexTaskEntity();
        task.setId(KnowledgeValues.id());
        task.setDocumentId(batch.getDocumentId());
        task.setVersionId(batch.getVersionId());
        task.setBatchId(batch.getId());
        task.setKind(kind);
        task.setProfileKey(profile);
        task.setStatus("PENDING");
        task.setStage("QUEUED");
        task.setIndexName("");
        task.setDimensions(0);
        task.setCompletedChunks(0);
        task.setAttemptCount(0);
        task.setNextRetryAt(0L);
        task.setLeaseOwner("");
        task.setLeaseUntil(0L);
        task.setHeartbeatAt(0L);
        task.setErrorCode("");
        task.setCreatedAt(KnowledgeValues.now());
        tasks.insert(task);
        return task;
    }

    /** 文档页面只读取公开进度，不返回执行令牌。 */
    @Override
    public List<KnowledgeViews.Task> list(String documentId) {
        return tasks
                .selectList(
                        Wrappers.<IndexTaskEntity>lambdaQuery()
                                .eq(IndexTaskEntity::getDocumentId, documentId)
                                .orderByDesc(IndexTaskEntity::getCreatedAt))
                .stream()
                .map(
                        task ->
                                new KnowledgeViews.Task(
                                        task.getId(),
                                        task.getBatchId(),
                                        task.getKind(),
                                        task.getStatus(),
                                        task.getStage(),
                                        task.getCompletedChunks(),
                                        task.getAttemptCount(),
                                        task.getNextRetryAt(),
                                        task.getErrorCode(),
                                        task.getCreatedAt()))
                .toList();
    }

    /** 保留每次失败或中断的历史。 */
    @Override
    public List<KnowledgeViews.Attempt> attempts(String taskId) {
        return attempts
                .selectList(
                        Wrappers.<TaskAttemptEntity>lambdaQuery()
                                .eq(TaskAttemptEntity::getTaskId, taskId)
                                .orderByAsc(TaskAttemptEntity::getStartedAt))
                .stream()
                .map(
                        item ->
                                new KnowledgeViews.Attempt(
                                        item.getId(),
                                        item.getTaskId(),
                                        item.getAttemptNumber(),
                                        item.getStatus(),
                                        item.getStage(),
                                        item.getErrorCode(),
                                        item.getStartedAt(),
                                        item.getFinishedAt()))
                .toList();
    }

    /** 人工重试不能复活成功、已删除或过期任务；次数保留并开启新预算。 */
    @Override
    public void retry(String taskId) {
        IndexTaskEntity task = tasks.selectById(taskId);
        if (task == null
                || !Set.of("FAILED", "BLOCKED", "RETRY_WAIT").contains(task.getStatus())
                || tasks.retryableDocument(taskId) != 1) {
            throw new IllegalArgumentException("TASK_NOT_RETRYABLE");
        }
        int changed =
                tasks.update(
                        Wrappers.<IndexTaskEntity>lambdaUpdate()
                                .eq(IndexTaskEntity::getId, taskId)
                                .eq(IndexTaskEntity::getStatus, task.getStatus())
                                .set(IndexTaskEntity::getStatus, "PENDING")
                                .set(IndexTaskEntity::getNextRetryAt, 0L)
                                .set(IndexTaskEntity::getErrorCode, "")
                                .set(IndexTaskEntity::getBudgetStart, task.getAttemptCount())
                                .set(IndexTaskEntity::getFinishedAt, null));
        if (changed != 1) {
            throw new IllegalStateException("TASK_STATE_CHANGED");
        }
    }

    /** 取消任务时一并结束执行历史，失效工作线程不能留下永久运行中的尝试。 */
    @Override
    public void cancel(String documentId, String batchId) {
        String state = batchId == null ? "CANCELLED" : "SUPERSEDED";
        tasks.update(
                Wrappers.<IndexTaskEntity>lambdaUpdate()
                        .eq(IndexTaskEntity::getDocumentId, documentId)
                        .eq(batchId != null, IndexTaskEntity::getBatchId, batchId)
                        .eq(batchId != null, IndexTaskEntity::getKind, "VECTOR")
                        .notIn(
                                IndexTaskEntity::getStatus,
                                List.of("SUCCEEDED", "CANCELLED", "SUPERSEDED"))
                        .set(IndexTaskEntity::getStatus, state)
                        .set(IndexTaskEntity::getNextRetryAt, 0L)
                        .set(IndexTaskEntity::getFinishedAt, KnowledgeValues.now()));
        for (IndexTaskEntity task :
                tasks.selectList(
                        Wrappers.<IndexTaskEntity>lambdaQuery()
                                .eq(IndexTaskEntity::getDocumentId, documentId)
                                .eq(IndexTaskEntity::getStatus, state))) {
            attempts.update(
                    Wrappers.<TaskAttemptEntity>lambdaUpdate()
                            .eq(TaskAttemptEntity::getTaskId, task.getId())
                            .eq(TaskAttemptEntity::getStatus, "RUNNING")
                            .set(TaskAttemptEntity::getStatus, state)
                            .set(TaskAttemptEntity::getFinishedAt, KnowledgeValues.now()));
        }
    }

    /** 领取条件带原尝试次数及到期条件，多个实例至多一人获得同一令牌。 */
    @Override
    public IndexTaskEntity claim() {
        long now = System.currentTimeMillis();
        // 待执行/到期重试任务，以及租约已过期的 RUNNING 任务，统一进入恢复队列。
        List<IndexTaskEntity> due =
                tasks.selectList(
                        Wrappers.<IndexTaskEntity>lambdaQuery()
                                .and(
                                        query ->
                                                query.in(
                                                                IndexTaskEntity::getStatus,
                                                                List.of("PENDING", "RETRY_WAIT"))
                                                        .le(IndexTaskEntity::getNextRetryAt, now)
                                                        .or()
                                                        .eq(IndexTaskEntity::getStatus, "RUNNING")
                                                        .lt(IndexTaskEntity::getLeaseUntil, now))
                                .orderByAsc(IndexTaskEntity::getNextRetryAt)
                                .last("LIMIT 20"));
        for (IndexTaskEntity task : due) {
            // 查询只是候选集；领取时仍以旧状态和旧租约做条件更新，跨实例竞争只允许一方成功。
            Boolean claimed = transactions.execute(status -> claimOne(task, now));
            if (Boolean.TRUE.equals(claimed)) {
                return task;
            }
        }
        return null;
    }

    /** 在领取事务中关闭失联的旧尝试并创建新执行记录。 */
    private boolean claimOne(IndexTaskEntity task, long now) {
        // 人工重试只推进预算起点，累计尝试次数和此前错误记录仍然保留。
        if (task.getAttemptCount() - task.getBudgetStart() >= maxAttempts) {
            int exhausted =
                    tasks.update(
                            Wrappers.<IndexTaskEntity>lambdaUpdate()
                                    .eq(IndexTaskEntity::getId, task.getId())
                                    .eq(IndexTaskEntity::getStatus, task.getStatus())
                                    .eq(IndexTaskEntity::getAttemptCount, task.getAttemptCount())
                                    .eq(IndexTaskEntity::getLeaseUntil, task.getLeaseUntil())
                                    .set(IndexTaskEntity::getStatus, "FAILED")
                                    .set(IndexTaskEntity::getErrorCode, "RETRY_EXHAUSTED")
                                    .set(IndexTaskEntity::getFinishedAt, KnowledgeValues.now()));
            if (exhausted == 1) {
                task.setErrorCode("RETRY_EXHAUSTED");
                attempts.update(
                        Wrappers.<TaskAttemptEntity>lambdaUpdate()
                                .eq(TaskAttemptEntity::getTaskId, task.getId())
                                .eq(TaskAttemptEntity::getStatus, "RUNNING")
                                .set(TaskAttemptEntity::getStatus, "INTERRUPTED")
                                .set(TaskAttemptEntity::getErrorCode, "LEASE_EXPIRED")
                                .set(TaskAttemptEntity::getFinishedAt, KnowledgeValues.now()));
                batchStatus(task, "FAILED");
            }
            return false;
        }
        String oldStatus = task.getStatus();
        String owner = KnowledgeValues.id();
        int changed =
                tasks.update(
                        Wrappers.<IndexTaskEntity>lambdaUpdate()
                                .eq(IndexTaskEntity::getId, task.getId())
                                .eq(IndexTaskEntity::getStatus, oldStatus)
                                .eq(IndexTaskEntity::getAttemptCount, task.getAttemptCount())
                                .eq(IndexTaskEntity::getLeaseOwner, task.getLeaseOwner())
                                .eq(IndexTaskEntity::getLeaseUntil, task.getLeaseUntil())
                                .set(IndexTaskEntity::getStatus, "RUNNING")
                                .set(IndexTaskEntity::getLeaseOwner, owner)
                                .set(IndexTaskEntity::getLeaseUntil, now + leaseMs)
                                .set(IndexTaskEntity::getHeartbeatAt, now)
                                .set(IndexTaskEntity::getAttemptCount, task.getAttemptCount() + 1)
                                .set(IndexTaskEntity::getStage, "STARTING"));
        if (changed != 1) {
            return false;
        }
        // 已获得新的执行令牌后，才结束旧尝试并保存新尝试，三者属于同一个事务。
        attempts.update(
                Wrappers.<TaskAttemptEntity>lambdaUpdate()
                        .eq(TaskAttemptEntity::getTaskId, task.getId())
                        .eq(TaskAttemptEntity::getStatus, "RUNNING")
                        .set(TaskAttemptEntity::getStatus, "INTERRUPTED")
                        .set(TaskAttemptEntity::getErrorCode, "LEASE_EXPIRED")
                        .set(TaskAttemptEntity::getFinishedAt, KnowledgeValues.now()));
        task.setStatus("RUNNING");
        task.setLeaseOwner(owner);
        task.setLeaseUntil(now + leaseMs);
        task.setHeartbeatAt(now);
        task.setAttemptCount(task.getAttemptCount() + 1);
        TaskAttemptEntity attempt = new TaskAttemptEntity();
        attempt.setId(owner);
        attempt.setTaskId(task.getId());
        attempt.setAttemptNumber(task.getAttemptCount());
        attempt.setLeaseOwner(owner);
        attempt.setStatus("RUNNING");
        attempt.setStage("STARTING");
        attempt.setErrorCode("");
        attempt.setStartedAt(KnowledgeValues.now());
        attempts.insert(attempt);
        batchStatus(task, "RUNNING");
        return true;
    }

    /** 心跳只延长当前有效租约，已过期的执行者不能自我复活。 */
    @Override
    public boolean heartbeat(IndexTaskEntity task) {
        long now = System.currentTimeMillis();
        return tasks.update(
                        Wrappers.<IndexTaskEntity>lambdaUpdate()
                                .eq(IndexTaskEntity::getId, task.getId())
                                .eq(IndexTaskEntity::getStatus, "RUNNING")
                                .eq(IndexTaskEntity::getLeaseOwner, task.getLeaseOwner())
                                .ge(IndexTaskEntity::getLeaseUntil, now)
                                .set(IndexTaskEntity::getHeartbeatAt, now)
                                .set(IndexTaskEntity::getLeaseUntil, now + leaseMs))
                == 1;
    }

    /** 发布事务也调用此条件检查，旧执行者不得回写。 */
    @Override
    public void requireLease(IndexTaskEntity task) {
        // 在发布事务中，这把行锁将租约核对与发布绑定；网络调用之间的检查则用于尽早停止。
        IndexTaskEntity current = tasks.lock(task.getId());
        if (Thread.currentThread().isInterrupted()
                || current == null
                || !current.getStatus().equals("RUNNING")
                || !current.getLeaseOwner().equals(task.getLeaseOwner())
                || current.getLeaseUntil() < System.currentTimeMillis()) {
            throw new IllegalStateException("TASK_LEASE_LOST");
        }
    }

    /** 在远程批次之间保存实际确认数量，不重复累计。 */
    @Override
    public void progress(IndexTaskEntity task, String stage, int completed) {
        requireLease(task);
        task.setStage(stage);
        task.setCompletedChunks(completed);
        int changed =
                tasks.update(
                        Wrappers.<IndexTaskEntity>lambdaUpdate()
                                .eq(IndexTaskEntity::getId, task.getId())
                                .eq(IndexTaskEntity::getLeaseOwner, task.getLeaseOwner())
                                .eq(IndexTaskEntity::getStatus, "RUNNING")
                                .set(IndexTaskEntity::getStage, stage)
                                .set(IndexTaskEntity::getCompletedChunks, completed)
                                .set(IndexTaskEntity::getIndexName, task.getIndexName())
                                .set(IndexTaskEntity::getDimensions, task.getDimensions())
                                .set(IndexTaskEntity::getProfileKey, task.getProfileKey()));
        if (changed != 1) {
            throw new IllegalStateException("TASK_LEASE_LOST");
        }
    }

    /** 重试失败保留旧可用索引；耗尽预算后等待人工处理。 */
    @Override
    public void finish(IndexTaskEntity task, Throwable error) {
        String code = error == null ? "" : KnowledgeValues.error(error);
        String state = error == null ? "SUCCEEDED" : failureState(task, code);
        long next = nextRetryAt(task, state);
        transactions.executeWithoutResult(
                status -> {
                    // 状态和执行令牌必须同时匹配；旧工作线程返回成功也不能覆盖新的尝试。
                    int changed =
                            tasks.update(
                                    Wrappers.<IndexTaskEntity>lambdaUpdate()
                                            .eq(IndexTaskEntity::getId, task.getId())
                                            .eq(
                                                    IndexTaskEntity::getLeaseOwner,
                                                    task.getLeaseOwner())
                                            .eq(IndexTaskEntity::getStatus, "RUNNING")
                                            .ge(
                                                    IndexTaskEntity::getLeaseUntil,
                                                    System.currentTimeMillis())
                                            .set(IndexTaskEntity::getStatus, state)
                                            .set(IndexTaskEntity::getErrorCode, code)
                                            .set(IndexTaskEntity::getNextRetryAt, next)
                                            .set(
                                                    IndexTaskEntity::getFinishedAt,
                                                    KnowledgeValues.now()));
                    if (changed == 1) {
                        task.setErrorCode(code);
                        attempts.update(
                                Wrappers.<TaskAttemptEntity>lambdaUpdate()
                                        .eq(TaskAttemptEntity::getId, task.getLeaseOwner())
                                        .set(TaskAttemptEntity::getStatus, state)
                                        .set(TaskAttemptEntity::getStage, task.getStage())
                                        .set(TaskAttemptEntity::getErrorCode, code)
                                        .set(
                                                TaskAttemptEntity::getFinishedAt,
                                                KnowledgeValues.now()));
                        batchStatus(task, state);
                    }
                });
    }

    /** 只有暂时失败安排补偿；固定退避叠加少量抖动，避免大量任务同时重试。 */
    private long nextRetryAt(IndexTaskEntity task, String state) {
        if (!state.equals("RETRY_WAIT")) {
            return 0;
        }
        long[] delayMultipliers = {1, 5, 15, 60};
        int attemptsInBudget = task.getAttemptCount() - task.getBudgetStart();
        int delayIndex = Math.min(Math.max(attemptsInBudget - 1, 0), delayMultipliers.length - 1);
        long delay = delayMultipliers[delayIndex] * retryBaseMs;
        long jitter = ThreadLocalRandom.current().nextLong(Math.max(1, delay / 10));
        return System.currentTimeMillis() + delay + jitter;
    }

    /** 配置和输入问题不进行无意义的定时模型调用。 */
    private String failureState(IndexTaskEntity task, String code) {
        if (code.equals("TASK_SUPERSEDED")) {
            return "SUPERSEDED";
        }
        if (code.equals("DOCUMENT_DELETED")) {
            return "CANCELLED";
        }
        if (code.contains("NOT_CONFIGURED")
                || (code.startsWith("EMBEDDING_")
                        && !code.startsWith("EMBEDDING_HTTP_")
                        && !Set.of("EMBEDDING_UNAVAILABLE", "EMBEDDING_INTERRUPTED").contains(code))
                || (code.matches("(EMBEDDING|ES)_HTTP_4[0-9]{2}") && !code.endsWith("_429"))
                || code.contains("HTTP_401")
                || code.contains("HTTP_403")
                || code.contains("INVALID")
                || code.contains("DIMENSION")
                || code.startsWith("EXCEL_")
                || code.startsWith("DOCUMENT_PARSE")
                || code.equals("DOCUMENT_MUST_BE_UTF8")
                || code.equals("PDF_TOO_LONG")
                || code.equals("TOO_MANY_CHUNKS")
                || code.equals("ORIGINAL_FILE_MISSING")
                || code.equals("NO_EXTRACTABLE_TEXT")) {
            return "BLOCKED";
        }
        return task.getAttemptCount() - task.getBudgetStart() >= maxAttempts
                ? "FAILED"
                : "RETRY_WAIT";
    }

    /** 汇总状态属于具体批次，不改写其他修订的可用性。 */
    private void batchStatus(IndexTaskEntity task, String state) {
        if (task.getKind().equals("IMPORT")
                && Set.of("FAILED", "BLOCKED", "CANCELLED").contains(state)) {
            batches.update(
                    Wrappers.<ProcessingBatchEntity>lambdaUpdate()
                            .eq(ProcessingBatchEntity::getId, task.getBatchId())
                            .notIn(
                                    ProcessingBatchEntity::getStatus,
                                    List.of("READY", "PREVIEW", "CANCELLED"))
                            .set(
                                    ProcessingBatchEntity::getStatus,
                                    state.equals("CANCELLED") ? "CANCELLED" : "FAILED")
                            .set(ProcessingBatchEntity::getErrorCode, task.getErrorCode()));
        } else if (task.getKind().equals("VECTOR")) {
            batches.update(
                    Wrappers.<ProcessingBatchEntity>lambdaUpdate()
                            .eq(ProcessingBatchEntity::getId, task.getBatchId())
                            .set(ProcessingBatchEntity::getVectorStatus, state));
        } else if (task.getKind().equals("TEXT")) {
            batches.update(
                    Wrappers.<ProcessingBatchEntity>lambdaUpdate()
                            .eq(ProcessingBatchEntity::getId, task.getBatchId())
                            .set(ProcessingBatchEntity::getTextStatus, state));
        }
    }
}
