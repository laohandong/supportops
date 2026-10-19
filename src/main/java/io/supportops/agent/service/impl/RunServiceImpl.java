package io.supportops.agent.service.impl;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.supportops.user.CurrentUser;
import io.supportops.user.vo.UserView;
import io.supportops.integration.service.ApplicationService;
import io.supportops.integration.service.DiagnosticTargetService;
import io.supportops.integration.service.support.EnterpriseIds;
import io.supportops.integration.service.support.EnvironmentAdmission;
import io.supportops.integration.service.support.IntegrationInputs;
import io.supportops.integration.vo.ApplicationPrincipal;
import io.supportops.integration.vo.DiagnosticScope;
import java.util.Objects;
import io.supportops.agent.constant.RunLimits;
import io.supportops.agent.constant.RunStatus;
import io.supportops.agent.entity.DiagnosisRunEntity;
import io.supportops.agent.entity.RunEventEntity;
import io.supportops.agent.mapper.DiagnosisRunMapper;
import io.supportops.agent.mapper.RunEventMapper;
import io.supportops.agent.memory.ConversationTurn;
import io.supportops.agent.service.DiagnosticEngine;
import io.supportops.agent.service.RunService;
import io.supportops.agent.service.support.RunStreamSignals;
import io.supportops.agent.vo.DiagnosisEvent;
import io.supportops.agent.vo.DiagnosisRun;
import io.supportops.agent.vo.ExecutionErrorEvent;
import io.supportops.memory.service.MemoryService;

import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** 通过 Mapper 持久化诊断状态，在虚拟线程中执行模型并保护事件与终态。 */
@Service
@DependsOn("deploymentRuntimeGuard")
public class RunServiceImpl implements RunService {
    /** 企业目标和未捕获目标的历史记录不能驱动内置示例修复。 */
    @Override
    public void requireDemoRemediationSource(String runId) {
        get(runId);
        DiagnosticScope scope = targets == null ? null : targets.snapshot(runId);
        if (scope == null || !EnterpriseIds.ENVIRONMENT.equals(scope.target().environmentId())
                || !EnterpriseIds.CONNECTOR.equals(scope.target().connectorRevisionId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "REMEDIATION_SOURCE_TARGET_UNCONFIRMED");
        }
    }
    private final DiagnosisRunMapper runs;
    private final RunEventMapper events;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final DiagnosticEngine engine;
    private final MemoryService memories;
    private final long timeoutSeconds;
    private final RunStreamSignals streamSignals;
    private final DiagnosticTargetService targets;
    private final EnvironmentAdmission admission;
    private final ApplicationService applications;
    private final ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService timer =
            Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon().name("diagnosis-deadline").factory());
    private final Map<String, Task> tasks = new ConcurrentHashMap<>();
    private final AtomicBoolean manualOperation = new AtomicBoolean();

    /** 单个任务的资源与终态保护，仅在该任务的监视器内修改执行标记。 */
    private static class Task {
        private final AtomicBoolean terminal = new AtomicBoolean();
        private final long started = System.nanoTime();
        private boolean entered;
        private volatile FutureTask<Void> future;
        private volatile ScheduledFuture<?> deadline;
        private DiagnosticScope scope;
        private ApplicationPrincipal application;
        private final AtomicBoolean released = new AtomicBoolean();
    }

    /** 注入持久化与执行边界，并将上次进程遗留的活动任务标记为中断。 */
    public RunServiceImpl(
            DiagnosisRunMapper runs,
            RunEventMapper events,
            TransactionTemplate transactions,
            ObjectMapper json,
            DiagnosticEngine engine,
            MemoryService memories,
            RunStreamSignals streamSignals,
            @Value("${supportops.model.timeout-seconds}") long timeoutSeconds) {
        this(runs, events, transactions, json, engine, memories, streamSignals, timeoutSeconds, null, null, null);
    }

    /** 企业准入复用既有生命周期；guard 的初始化必须先于遗留任务中断。 */
    @Autowired
    public RunServiceImpl(DiagnosisRunMapper runs, RunEventMapper events, TransactionTemplate transactions,
            ObjectMapper json, DiagnosticEngine engine, MemoryService memories, RunStreamSignals streamSignals,
            @Value("${supportops.model.timeout-seconds}") long timeoutSeconds, DiagnosticTargetService targets,
            EnvironmentAdmission admission, ApplicationService applications) {
        this.runs = runs;
        this.events = events;
        this.transactions = transactions;
        this.json = json;
        this.engine = engine;
        this.memories = memories;
        this.streamSignals = streamSignals;
        this.timeoutSeconds = timeoutSeconds;
        this.targets = targets;
        this.admission = admission;
        this.applications = applications;
        runs.update(
                Wrappers.<DiagnosisRunEntity>lambdaUpdate()
                        .in(
                                DiagnosisRunEntity::getStatus,
                                RunStatus.QUEUED.name(),
                                RunStatus.RUNNING.name())
                        .set(DiagnosisRunEntity::getStatus, RunStatus.INTERRUPTED.name())
                        .set(DiagnosisRunEntity::getErrorCode, "PROCESS_RESTARTED")
                        .set(DiagnosisRunEntity::getFinishedAt, Instant.now().toString()));
    }

    /** 在同一准入锁内完成校验、持久化与任务注册，禁止同时修改现场。 */
    @Override
    public DiagnosisRun start(String question, String sessionId, boolean lexicalOnly) {
        if (targets != null && CurrentUser.get() != null) {
            return startTarget(question, sessionId, lexicalOnly, null);
        }
        requireManualIdle();
        synchronized (this) {
            requireManualIdle();
            return startOwned(UUID.randomUUID().toString(), question, validateSessionId(sessionId),
                    CurrentUser.id(), lexicalOnly);
        }
    }

    /** 每次显式解析服务器目标，同会话不能切换环境。 */
    @Override
    public DiagnosisRun startTarget(String question, String sessionId, boolean lexicalOnly, String environmentId) {
        if (targets == null || (environmentId == null && CurrentUser.get() == null)) {
            return start(question, sessionId, lexicalOnly);
        }
        DiagnosticScope scope = targets.user(environmentId == null ? EnterpriseIds.ENVIRONMENT : environmentId, CurrentUser.get());
        synchronized (this) {
            return transactions.execute(transaction -> startScoped(UUID.randomUUID().toString(), question,
                    validateSessionId(sessionId), lexicalOnly, scope, null));
        }
    }

    /** 开放平台回执事务包裹此方法，任务不会在提交前开始调用现场或模型。 */
    @Override
    public synchronized DiagnosisRun startApplication(String id, String question, boolean lexicalOnly,
            ApplicationPrincipal actor, DiagnosticScope scope) {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || targets == null
                || !"APPLICATION".equals(scope.actorType()) || !actor.applicationId().equals(scope.actorId())) {
            throw IntegrationInputs.error(409, "APPLICATION_ACCEPTANCE_TRANSACTION_REQUIRED");
        }
        return startScoped(id, question, id, lexicalOnly, scope, actor);
    }

    /** 在短事务内保存 run 和目标并注册提交后执行；失败释放全部名额。 */
    private DiagnosisRun startScoped(String id, String question, String session, boolean lexicalOnly,
            DiagnosticScope scope, ApplicationPrincipal actor) {
        if (!engine.configured()) { throw IntegrationInputs.error(503, "MODEL_NOT_CONFIGURED"); }
        IntegrationInputs.text(question, RunLimits.QUESTION_LENGTH);
        targets.check(scope, null);
        String userId = "USER".equals(scope.actorType()) ? scope.actorId() : null;
        if (actor == null) {
            requireSessionOwner(session, userId);
            for (DiagnosisRunEntity previous : runs.selectList(Wrappers.<DiagnosisRunEntity>lambdaQuery().eq(DiagnosisRunEntity::getSessionId, session))) {
                DiagnosticScope historical = targets.snapshot(previous.getId());
                String historicalEnvironment = historical == null ? EnterpriseIds.ENVIRONMENT : historical.target().environmentId();
                if (!historicalEnvironment.equals(scope.target().environmentId())) { throw IntegrationInputs.error(409, "SESSION_TARGET_CONFLICT"); }
            }
        }
        admission.acquire(scope.target().environmentId(), actor != null);
        Task task = new Task(); task.scope = scope;
        try {
            if (actor != null) { applications.acquire(actor); task.application = actor; }
            DiagnosisRunEntity record = new DiagnosisRunEntity(id, session, question, RunStatus.QUEUED.name(), "", "",
                    Instant.now().toString(), null, 0L, 0L, 0L);
            record.setUserId(userId); record.setApplicationId(actor == null ? null : actor.applicationId()); record.setActorType(scope.actorType());
            requireSingleWrite(runs.insert(record)); targets.save(id, scope);
            DiagnosticEngine.Context context = new DiagnosticEngine.Context(question,
                    actor == null ? history(session) : List.of(), scope.documentChatAllowed()
                            ? memories.scoped(scope.target().projectId(), scope.target().systemId(), scope.target().environmentId())
                            : List.of(), lexicalOnly, scope, id);
            task.future = new FutureTask<>(() -> { execute(id, task, context); return null; });
            tasks.put(id, task);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                /** 提交之后才执行外部副作用，重启仅保留中断事实。 */
                @Override public void afterCommit() {
                    task.deadline = timer.schedule(() -> stop(id, task, RunStatus.TIMED_OUT, "TIME_BUDGET_EXCEEDED"), timeoutSeconds, TimeUnit.SECONDS);
                    worker.execute(task.future);
                }
                /** 回滚时清除内存登记并归还名额。 */
                @Override public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) { tasks.remove(id, task); release(task); }
                }
            });
            return toView(record);
        } catch (RuntimeException exception) {
            tasks.remove(id, task); release(task); throw exception;
        }
    }

    /** 外部请求编号由渠道先落库，检索遵循统一配置；重启或重复投递只返回同一任务。 */
    @Override
    public DiagnosisRun startExternal(
            String requestId, String question, UserView owner) {
        return startExternalTarget(requestId, question, owner, EnterpriseIds.ENVIRONMENT);
    }
    /** 渠道原请求已捕获目标，重试只读取原记录，不重建现场快照。 */
    @Override public DiagnosisRun startExternalTarget(String requestId, String question, UserView owner, String environmentId) {
        DiagnosisRun existing = existingExternal(requestId, question, owner);
        if (existing != null) {
            return existing;
        }
        requireManualIdle();
        synchronized (this) {
            existing = existingExternal(requestId, question, owner);
            if (existing != null) {
                return existing;
            }
            requireManualIdle();
            // 渠道遵循统一检索配置；新的任务才需要通过现场准入。
            if (targets != null) {
                DiagnosticScope scope = targets.user(environmentId, owner);
                return transactions.execute(transaction -> startScoped(requestId, question, requestId, false, scope, null));
            }
            return startOwned(requestId, question, requestId, owner.id(), false);
        }
    }

    /** 只读重放保留渠道的固定请求语义，不因人工修复正在执行而阻断旧结果读取。 */
    private DiagnosisRun existingExternal(String requestId, String question, UserView owner) {
        if (owner == null || owner.id() == null || requestId == null || requestId.isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "INVALID_EXTERNAL_IDENTITY");
        }
        validateSessionId(requestId);
        DiagnosisRunEntity existing = runs.selectById(requestId);
        if (existing != null) {
            if (!owner.id().equals(existing.getUserId()) || !Objects.equals(question, existing.getQuestion())) {
                throw new ResponseStatusException(CONFLICT, "EXTERNAL_REQUEST_CONFLICT");
            }
            return toView(existing);
        }
        return null;
    }

    /** 在准入锁内保存明确归属；渠道与网页复用执行、超时、事件和终态语义。 */
    private DiagnosisRun startOwned(
            String id, String question, String effectiveSession, String userId, boolean lexicalOnly) {
        if (!engine.configured()) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "MODEL_NOT_CONFIGURED");
        }
        if (question == null
                || question.isBlank()
                || question.length() > RunLimits.QUESTION_LENGTH) {
            throw new ResponseStatusException(BAD_REQUEST, "INVALID_QUESTION");
        }
        requireSessionOwner(effectiveSession, userId);
        requireIdle();
        Task task = new Task();
        DiagnosticEngine.Context context =
                new DiagnosticEngine.Context(
                        question, history(effectiveSession), memories.list(), lexicalOnly);
        DiagnosisRunEntity record =
                new DiagnosisRunEntity(
                        id,
                        effectiveSession,
                        question,
                        RunStatus.QUEUED.name(),
                        "",
                        "",
                        Instant.now().toString(),
                        null,
                        0L,
                        0L,
                        0L);
        record.setUserId(userId);
        requireSingleWrite(runs.insert(record));
        task.future =
                new FutureTask<>(
                        () -> {
                            execute(id, task, context);
                            return null;
                        });
        tasks.put(id, task);
        task.deadline =
                timer.schedule(
                        () -> stop(id, task, RunStatus.TIMED_OUT, "TIME_BUDGET_EXCEEDED"),
                        timeoutSeconds,
                        TimeUnit.SECONDS);
        worker.execute(task.future);
        return toView(runs.selectById(id));
    }

    /** 会话只允许原提交人续聊；管理员可回看他人记录，但不能混入其上下文。 */
    private void requireSessionOwner(String sessionId, String userId) {
        List<DiagnosisRunEntity> records = runs.selectList(Wrappers.<DiagnosisRunEntity>lambdaQuery()
                .eq(DiagnosisRunEntity::getSessionId, sessionId));
        if (records.stream().anyMatch(record -> !Objects.equals(record.getUserId(), userId))) {
            throw new ResponseStatusException(NOT_FOUND, "RUN_NOT_FOUND");
        }
        if (records.stream().anyMatch(record -> record.getArchivedAt() != null)) {
            throw new ResponseStatusException(CONFLICT, "SESSION_ARCHIVED");
        }
    }

    /** 只复用三轮已完成问答，并按发生顺序组装受限长度的上下文。 */
    private List<ConversationTurn> history(String sessionId) {
        List<ConversationTurn> history = new ArrayList<>();
        for (ConversationTurn turn : runs.selectRecentHistory(sessionId)) {
            history.add(
                    new ConversationTurn(
                            turn.question(),
                            truncate(turn.answer(), RunLimits.HISTORY_ANSWER_LENGTH)));
        }
        Collections.reverse(history);
        return history;
    }

    /** 未提供会话时生成 UUID；已有会话必须满足 UUID 格式。 */
    private String validateSessionId(String sessionId) {
        String value =
                sessionId == null || sessionId.isBlank() ? UUID.randomUUID().toString() : sessionId;
        try {
            UUID.fromString(value);
            return value;
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(BAD_REQUEST, "INVALID_SESSION_ID");
        }
    }

    /** 取消状态写入后仍需等待工作线程释放资源，期间拒绝新的现场变更。 */
    @Override
    public synchronized void requireIdle() {
        if (!tasks.isEmpty()) {
            throw new ResponseStatusException(CONFLICT, "DIAGNOSIS_IN_PROGRESS");
        }
    }

    /** 将空闲检查与人工操作纳入同一锁，避免检查与使用之间的竞争。 */
    @Override
    public <T> T whenIdle(Supplier<T> operation) {
        requireManualIdle();
        if (tasks.values().stream().anyMatch(task -> task.scope == null)) { throw new ResponseStatusException(CONFLICT, "DIAGNOSIS_IN_PROGRESS"); }
        if (admission != null) { return admission.environment(EnterpriseIds.ENVIRONMENT, operation); }
        if (!manualOperation.compareAndSet(false, true)) {
            throw new ResponseStatusException(CONFLICT, "ENVIRONMENT_OPERATION_IN_PROGRESS");
        }
        try {
            synchronized (this) {
                requireIdle();
                return operation.get();
            }
        } finally {
            manualOperation.set(false);
        }
    }

    /** 模型配置和运维需要全部环境空闲。 */
    @Override public <T> T whenGloballyIdle(Supplier<T> operation) {
        requireManualIdle();
        if (tasks.values().stream().anyMatch(task -> task.scope == null)) { throw new ResponseStatusException(CONFLICT, "DIAGNOSIS_IN_PROGRESS"); }
        return admission == null ? whenIdle(operation) : admission.globally(operation);
    }

    /** 人工操作期间立即拒绝新诊断，避免等待结束后意外受理。 */
    private void requireManualIdle() {
        if (manualOperation.get()) {
            throw new ResponseStatusException(CONFLICT, "ENVIRONMENT_OPERATION_IN_PROGRESS");
        }
    }

    /** 执行模型并记录安全的异常位置，结束后始终回收计时器和任务登记。 */
    private void execute(String id, Task task, DiagnosticEngine.Context context) {
        try {
            synchronized (task) {
                if (task.terminal.get()) {
                    return;
                }
                task.entered = true;
                requireSingleWrite(
                        runs.update(
                                Wrappers.<DiagnosisRunEntity>lambdaUpdate()
                                        .eq(DiagnosisRunEntity::getId, id)
                                        .eq(DiagnosisRunEntity::getStatus, RunStatus.QUEUED.name())
                                        .set(
                                                DiagnosisRunEntity::getStatus,
                                                RunStatus.RUNNING.name())));
            }
            // RUNNING 已落库，等待模型首段时也能及时展示执行状态。
            streamSignals.changed(id);
            DiagnosticEngine.Result result =
                    engine.execute(
                            context, (kind, content) -> recordEvent(id, task, kind, content));
            RunStatus status =
                    result.stepLimitReached() ? RunStatus.LIMIT_REACHED : RunStatus.COMPLETED;
            finish(
                    id,
                    task,
                    status,
                    result.answer(),
                    result.stepLimitReached() ? "STEP_LIMIT_REACHED" : "");
        } catch (Exception exception) {
            // 只保存异常类型与代码位置，避免把服务商响应、提示词或密钥写入事件。
            recordEvent(
                    id,
                    task,
                    "ERROR",
                    new ExecutionErrorEvent(
                            exception.getClass().getSimpleName(),
                            Arrays.stream(exception.getStackTrace())
                                    .limit(4)
                                    .map(Object::toString)
                                    .toList()));
            finish(id, task, RunStatus.FAILED, "", failureCode(exception));
        } finally {
            if (task.deadline != null) {
                task.deadline.cancel(false);
            }
            tasks.remove(id, task);
            release(task);
        }
    }

    /** 只透出允许公开的执行错误码，其余异常统一归为诊断失败。 */
    private String failureCode(Exception exception) {
        if (exception instanceof ResponseStatusException status && status.getReason() != null
                && status.getReason().matches("(TARGET|CAPABILITY|AUTHORIZATION|DATA_EXTERNALIZATION|APPLICATION_AUTHORIZATION|CREDENTIAL|MASTER_KEY)_[A-Z_]+")) {
            return status.getReason();
        }
        Set<String> allowed =
                Set.of("EMPTY_MODEL_RESPONSE", "STEP_LIMIT_REACHED", "MODEL_HTTPS_REQUIRED");
        if (exception instanceof IllegalStateException
                && allowed.contains(String.valueOf(exception.getMessage()))) {
            return exception.getMessage();
        }
        return "DIAGNOSIS_EXECUTION_FAILED";
    }

    /** 在短事务中同时写入事件和累计用量，保证失败时不会只保存其中一项。 */
    private void recordEvent(String id, Task task, String kind, Object content) {
        synchronized (task) {
            if (task.terminal.get()) {
                return;
            }
            try {
                String payload = json.writeValueAsString(content);
                transactions.executeWithoutResult(
                        transaction -> {
                            requireSingleWrite(
                                    events.insert(
                                            new RunEventEntity(
                                                    null,
                                                    id,
                                                    kind,
                                                    payload,
                                                    Instant.now().toString())));
                            if ("USAGE".equals(kind)) {
                                JsonNode usage = json.valueToTree(content);
                                requireSingleWrite(
                                        runs.incrementUsage(
                                                id,
                                                usage.path("inputTokens").asLong(),
                                                usage.path("outputTokens").asLong()));
                            }
                        });
                // 事务已经提交，唤醒后读取的游标不会指向尚未落库的片段。
                streamSignals.changed(id);
            } catch (Exception exception) {
                throw new IllegalStateException("EVENT_PERSISTENCE_FAILED");
            }
        }
    }

    /** 终态只落库一次；用量已经按事件累计，不能再次加入模型汇总值。 */
    private void finish(String id, Task task, RunStatus status, String answer, String error) {
        synchronized (task) {
            if (task.terminal.get()) {
                return;
            }
            DiagnosisRunEntity record = new DiagnosisRunEntity();
            record.setId(id);
            record.setStatus(status.name());
            record.setAnswer(answer);
            record.setErrorCode(error);
            record.setFinishedAt(Instant.now().toString());
            record.setElapsedMs((System.nanoTime() - task.started) / 1_000_000);
            requireSingleWrite(runs.finishActiveRun(record));
            task.terminal.set(true);
            streamSignals.changed(id);
        }
    }

    /** 先持久化终态，再中断工作线程，阻止迟到事件覆盖取消或超时结果。 */
    private void stop(String id, Task task, RunStatus status, String error) {
        synchronized (task) {
            finish(id, task, status, "", error);
            task.future.cancel(true);
            if (!task.entered) {
                tasks.remove(id, task);
                release(task);
            }
        }
    }

    /** 取消和工作线程退出可能竞争，资源名额最多归还一次。 */
    private void release(Task task) {
        if (task.scope != null && task.released.compareAndSet(false, true)) {
            admission.release(task.scope.target().environmentId());
            if (task.application != null) { applications.release(task.application); }
        }
    }

    /** 应用读取不能继承后台管理员或空请求身份。 */
    @Override public DiagnosisRun getApplication(String id, ApplicationPrincipal actor) {
        DiagnosisRunEntity value = runs.selectById(id);
        if (value == null || !actor.applicationId().equals(value.getApplicationId())) { throw IntegrationInputs.error(404, "RUN_NOT_FOUND"); }
        DiagnosticScope scope = targets.snapshot(id);
        if (scope == null) { throw IntegrationInputs.error(404, "RUN_NOT_FOUND"); }
        applications.authorize(actor, "READ", scope.target().environmentId());
        return toView(value);
    }
    /** 原始事件只供公开服务进一步白名单投影；数量在数据库查询时已限制。 */
    @Override public List<DiagnosisEvent> eventsApplication(String id, ApplicationPrincipal actor, long after, int limit) {
        getApplication(id, actor);
        if (after < 0 || limit < 1 || limit > 200) { throw IntegrationInputs.error(400, "INVALID_EVENT_CURSOR"); }
        return events.selectList(Wrappers.<RunEventEntity>lambdaQuery().eq(RunEventEntity::getRunId, id)
                .gt(RunEventEntity::getId, after).orderByAsc(RunEventEntity::getId).last("LIMIT " + limit)).stream().map(this::toEvent).toList();
    }
    /** 取消权限独立复核，重复取消不重复执行任务。 */
    @Override public DiagnosisRun cancelApplication(String id, ApplicationPrincipal actor) {
        getApplication(id, actor);
        applications.authorize(actor, "CANCEL", targets.snapshot(id).target().environmentId());
        Task task = tasks.get(id);
        if (task != null) { stop(id, task, RunStatus.CANCELLED, "CANCELLED_BY_APPLICATION"); }
        return getApplication(id, actor);
    }
    /** 只选择证据编号，不把原始模型事件暴露给终态结果。 */
    @Override public List<Long> evidenceIdsApplication(String id, ApplicationPrincipal actor) {
        getApplication(id, actor);
        return events.selectList(Wrappers.<RunEventEntity>lambdaQuery().select(RunEventEntity::getId)
                .eq(RunEventEntity::getRunId, id).in(RunEventEntity::getKind, "SOURCE_EVIDENCE", "KNOWLEDGE", "SQL_RESULT")
                .orderByAsc(RunEventEntity::getId).last("LIMIT 2000")).stream().map(RunEventEntity::getId).toList();
    }
    /** 保持既有未报告用量的语义。 */
    @Override public boolean usageReportedApplication(String id, ApplicationPrincipal actor) {
        getApplication(id, actor);
        return events.selectCount(Wrappers.<RunEventEntity>lambdaQuery().eq(RunEventEntity::getRunId, id)
                .eq(RunEventEntity::getKind, "USAGE")) > 0;
    }

    /** 取消活动任务；对已经完成的任务返回已有状态。 */
    @Override
    public DiagnosisRun cancel(String id) {
        get(id);
        Task task = tasks.get(id);
        if (task != null) {
            stop(id, task, RunStatus.CANCELLED, "CANCELLED_BY_USER");
        }
        return get(id);
    }

    /** 与诊断准入共用锁，按整个会话原子归档；保留问答、事件和统计。 */
    @Override
    public synchronized void archive(String id) {
        DiagnosisRun run = get(id);
        transactions.executeWithoutResult(transaction -> {
            List<DiagnosisRunEntity> records = runs.selectList(
                    Wrappers.<DiagnosisRunEntity>lambdaQuery()
                            .eq(DiagnosisRunEntity::getSessionId, run.sessionId()).last("FOR UPDATE"));
            UserView user = CurrentUser.get();
            if (user != null && !user.isAdmin() && records.stream()
                    .anyMatch(record -> !user.id().equals(record.getUserId()))) {
                throw new ResponseStatusException(NOT_FOUND, "RUN_NOT_FOUND");
            }
            if (records.stream().anyMatch(record -> tasks.containsKey(record.getId())
                    || RunStatus.QUEUED.name().equals(record.getStatus())
                    || RunStatus.RUNNING.name().equals(record.getStatus()))) {
                throw new ResponseStatusException(CONFLICT, "DIAGNOSIS_IN_PROGRESS");
            }
            long pending = records.stream().filter(record -> record.getArchivedAt() == null).count();
            if (pending == 0) {
                return;
            }
            int affected = runs.update(Wrappers.<DiagnosisRunEntity>lambdaUpdate()
                    .eq(DiagnosisRunEntity::getSessionId, run.sessionId())
                    .isNull(DiagnosisRunEntity::getArchivedAt)
                    .set(DiagnosisRunEntity::getArchivedAt, Instant.now().toString()));
            if (affected != pending) {
                throw new IllegalStateException("RUN_STATE_CHANGED");
            }
        });
    }

    /** 查询未归档的最近任务，实体不会直接暴露给接口。 */
    @Override
    public List<DiagnosisRun> list() {
        UserView user = CurrentUser.get();
        return runs.selectList(Wrappers.<DiagnosisRunEntity>lambdaQuery()
                        .isNull(DiagnosisRunEntity::getArchivedAt)
                        .eq(user != null && !user.isAdmin(), DiagnosisRunEntity::getUserId,
                                user == null ? null : user.id())
                        .orderByDesc(DiagnosisRunEntity::getCreatedAt)
                        .orderByDesc(DiagnosisRunEntity::getId).last("LIMIT 100"))
                .stream().filter(record -> user == null || targets == null
                        || targets.historyAllowed(record.getId(), user.id(), user.isAdmin())).map(this::toView).toList();
    }

    /** 通过主键读取任务并保留原有未找到错误码。 */
    @Override
    public DiagnosisRun get(String id) {
        DiagnosisRunEntity record = runs.selectById(id);
        UserView user = CurrentUser.get();
        if (record == null || (user != null && ((!user.isAdmin() && !user.id().equals(record.getUserId()))
                || (targets != null && !targets.historyAllowed(id, user.id(), user.isAdmin()))))) {
            throw new ResponseStatusException(NOT_FOUND, "RUN_NOT_FOUND");
        }
        return toView(record);
    }

    /** 后台渠道不依赖 HTTP 请求上下文，始终按已绑定的用户编号校验归属。 */
    @Override
    public DiagnosisRun getOwned(String id, String userId) {
        DiagnosisRunEntity record = runs.selectById(id);
        if (userId == null || record == null || !userId.equals(record.getUserId())
                || (targets != null && !targets.historyAllowed(id, userId, false))) {
            throw new ResponseStatusException(NOT_FOUND, "RUN_NOT_FOUND");
        }
        return toView(record);
    }

    /** 处理结果公开服务不允许空身份；发布和回填在同一来源锁顺序下串行判定版本。 */
    @Override
    public DiagnosisRun sourceForOutcome(String id, UserView actor, boolean lock) {
        if (actor == null) {
            throw new ResponseStatusException(UNAUTHORIZED, "AUTHENTICATION_REQUIRED");
        }
        DiagnosisRunEntity record = lock ? runs.lockOutcomeSource(id) : runs.selectById(id);
        if (record == null || (!actor.isAdmin() && !actor.id().equals(record.getUserId()))
                || (targets != null && !targets.historyAllowed(id, actor.id(), actor.isAdmin()))) {
            throw new ResponseStatusException(NOT_FOUND, "RUN_NOT_FOUND");
        }
        return toView(record);
    }

    /** 按游标读取持久化事件，避免轮询重复返回已经消费的记录。 */
    @Override
    public List<DiagnosisEvent> events(String id, long after) {
        get(id);
        return events
                .selectList(
                        Wrappers.<RunEventEntity>lambdaQuery()
                                .eq(RunEventEntity::getRunId, id)
                                .gt(RunEventEntity::getId, after)
                                .orderByAsc(RunEventEntity::getId))
                .stream()
                .map(this::toEvent)
                .toList();
    }

    /** 将事件 JSON 还原为结构化响应，存储损坏时明确失败。 */
    private DiagnosisEvent toEvent(RunEventEntity record) {
        try {
            return new DiagnosisEvent(
                    record.getId(),
                    record.getKind(),
                    json.readTree(record.getContent()),
                    record.getCreatedAt());
        } catch (Exception exception) {
            throw new IllegalStateException("INVALID_STORED_EVENT");
        }
    }

    /** 保持诊断接口字段、时间与数值类型兼容。 */
    private DiagnosisRun toView(DiagnosisRunEntity record) {
        return new DiagnosisRun(
                record.getId(),
                record.getSessionId(),
                record.getQuestion(),
                record.getStatus(),
                record.getAnswer(),
                record.getErrorCode(),
                record.getCreatedAt(),
                record.getFinishedAt(),
                record.getElapsedMs(),
                record.getInputTokens(),
                record.getOutputTokens(),
                record.getUserId(), record.getApplicationId(), record.getActorType());
    }

    /** 单记录写入失败时阻止继续推进内存状态。 */
    private void requireSingleWrite(int affected) {
        if (affected != 1) {
            throw new IllegalStateException("RUN_STATE_CHANGED");
        }
    }

    /** 截断历史回答长度，避免跨轮上下文无限增长。 */
    private static String truncate(String value, int max) {
        return value.substring(0, Math.min(max, value.length()));
    }

    /** 应用关闭时中断任务，并释放线程与截止时间调度器。 */
    @Autowired
    public void protectGuard(io.supportops.integration.service.support.DeploymentRuntimeGuard guard) {
        guard.protect(tasks::isEmpty);
    }

    /** 等待实际线程退出后才允许释放持久化单实例执行权。 */
    @PreDestroy
    public void close() {
        tasks.forEach((id, task) -> stop(id, task, RunStatus.INTERRUPTED, "SERVER_SHUTDOWN"));
        worker.shutdownNow();
        timer.shutdownNow();
        try { worker.awaitTermination(15, TimeUnit.SECONDS); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
    }
}
