package io.supportops.agent.service.impl;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.supportops.user.CurrentUser;
import io.supportops.user.vo.UserView;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
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
public class RunServiceImpl implements RunService {
    private final DiagnosisRunMapper runs;
    private final RunEventMapper events;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final DiagnosticEngine engine;
    private final MemoryService memories;
    private final long timeoutSeconds;
    private final RunStreamSignals streamSignals;
    private final ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService timer =
            Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon().name("diagnosis-deadline").factory());
    private final Map<String, Task> tasks = new ConcurrentHashMap<>();

    /** 单个任务的资源与终态保护，仅在该任务的监视器内修改执行标记。 */
    private static class Task {
        private final AtomicBoolean terminal = new AtomicBoolean();
        private final long started = System.nanoTime();
        private boolean entered;
        private volatile FutureTask<Void> future;
        private volatile ScheduledFuture<?> deadline;
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
        this.runs = runs;
        this.events = events;
        this.transactions = transactions;
        this.json = json;
        this.engine = engine;
        this.memories = memories;
        this.streamSignals = streamSignals;
        this.timeoutSeconds = timeoutSeconds;
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
    public synchronized DiagnosisRun start(String question, String sessionId, boolean lexicalOnly) {
        if (!engine.configured()) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "MODEL_NOT_CONFIGURED");
        }
        if (question == null
                || question.isBlank()
                || question.length() > RunLimits.QUESTION_LENGTH) {
            throw new ResponseStatusException(BAD_REQUEST, "INVALID_QUESTION");
        }
        String effectiveSession = validateSessionId(sessionId);
        requireSessionOwner(effectiveSession);
        requireIdle();
        String id = UUID.randomUUID().toString();
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
        record.setUserId(CurrentUser.id());
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
        return get(id);
    }

    /** 会话只允许原提交人续聊；管理员可回看他人记录，但不能混入其上下文。 */
    private void requireSessionOwner(String sessionId) {
        String userId = CurrentUser.id();
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
    public synchronized <T> T whenIdle(Supplier<T> operation) {
        requireIdle();
        return operation.get();
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
        }
    }

    /** 只透出允许公开的执行错误码，其余异常统一归为诊断失败。 */
    private String failureCode(Exception exception) {
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
            }
        }
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
                .stream().map(this::toView).toList();
    }

    /** 通过主键读取任务并保留原有未找到错误码。 */
    @Override
    public DiagnosisRun get(String id) {
        DiagnosisRunEntity record = runs.selectById(id);
        UserView user = CurrentUser.get();
        if (record == null || (user != null && !user.isAdmin() && !user.id().equals(record.getUserId()))) {
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
                record.getUserId());
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
    @PreDestroy
    public void close() {
        tasks.forEach((id, task) -> stop(id, task, RunStatus.INTERRUPTED, "SERVER_SHUTDOWN"));
        worker.shutdownNow();
        timer.shutdownNow();
    }
}
