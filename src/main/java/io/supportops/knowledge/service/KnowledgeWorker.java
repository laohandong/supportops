package io.supportops.knowledge.service;

import io.supportops.knowledge.entity.IndexTaskEntity;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 定时器只负责领取和续租，实际 I/O 在有容量限制的虚拟线程中运行。 */
@Component
public class KnowledgeWorker {
    private static final Logger LOGGER = LoggerFactory.getLogger(KnowledgeWorker.class);
    private final KnowledgeTaskService tasks;
    private final DocumentLibraryService library;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService timer =
            Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon().name("knowledge-scheduler").factory());
    private final ConcurrentHashMap<String, Execution> active = new ConcurrentHashMap<>();
    private final AtomicBoolean scanning = new AtomicBoolean();
    private final boolean enabled;
    private final int concurrency;
    private final long scanMs;
    private final long heartbeatMs;
    private final long timeoutMs;
    private volatile boolean closed;

    /** 内存只保存当前资源，任务事实和恢复依据在 MySQL。 */
    private record Execution(IndexTaskEntity task, Thread thread, long started) {}

    /** 注入独立任务容量与调度参数。 */
    public KnowledgeWorker(
            KnowledgeTaskService tasks,
            DocumentLibraryService library,
            @Value("${supportops.knowledge.jobs.enabled:true}") boolean enabled,
            @Value("${supportops.knowledge.jobs.concurrency:2}") int concurrency,
            @Value("${supportops.knowledge.jobs.scan-ms:60000}") long scanMs,
            @Value("${supportops.knowledge.jobs.heartbeat-ms:30000}") long heartbeatMs,
            @Value("${supportops.knowledge.jobs.timeout-ms:1800000}") long timeoutMs) {
        this.tasks = tasks;
        this.library = library;
        this.enabled = enabled;
        this.concurrency = concurrency;
        this.scanMs = scanMs;
        this.heartbeatMs = heartbeatMs;
        this.timeoutMs = timeoutMs;
        if (concurrency < 1
                || concurrency > 32
                || scanMs < 100
                || heartbeatMs < 100
                || timeoutMs < heartbeatMs) {
            throw new IllegalArgumentException("INVALID_KNOWLEDGE_JOB_CONFIGURATION");
        }
    }

    /** 应用就绪后启动补偿扫描，不在构造器中访问尚未迁移的表。 */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!enabled) {
            return;
        }
        timer.scheduleWithFixedDelay(this::safeScan, 0, scanMs, TimeUnit.MILLISECONDS);
        timer.scheduleWithFixedDelay(this::pulse, heartbeatMs, heartbeatMs, TimeUnit.MILLISECONDS);
    }

    /** 事务提交后的即时触发与定时补偿共用领取入口。 */
    @EventListener
    public void submitted(KnowledgeSubmittedEvent event) {
        if (enabled && !closed) {
            timer.execute(this::safeScan);
        }
    }

    /** 扫描异常由下一次补偿继续处理，不能导致定时器永久停止。 */
    private void safeScan() {
        if (closed || !scanning.compareAndSet(false, true)) {
            return;
        }
        try {
            library.wakeConfiguredTasks();
            while (active.size() < concurrency && !closed) {
                // 领取权由数据库租约决定；虚拟线程数量仍受本实例容量限制，避免压垮外部服务。
                IndexTaskEntity task = tasks.claim();
                if (task == null) {
                    break;
                }
                Execution pending = new Execution(task, null, System.currentTimeMillis());
                active.put(task.getId(), pending);
                workers.submit(() -> execute(task));
            }
        } catch (Exception exception) {
            // 任务尚在数据库中；扫描失败不会伪造完成，也不向日志写 SQL 或配置。
            LOGGER.warn("KNOWLEDGE_SCAN_FAILED: {}", exception.getClass().getSimpleName());
        } finally {
            scanning.set(false);
        }
    }

    /** 每个文档执行一次，失败结果在租约有效时持久化。 */
    private void execute(IndexTaskEntity task) {
        active.put(
                task.getId(),
                new Execution(task, Thread.currentThread(), System.currentTimeMillis()));
        Throwable error = null;
        try {
            // 工作线程接收的是持久化任务，不依赖请求线程的 MultipartFile 或用户上下文。
            library.execute(task);
        } catch (Throwable exception) {
            error = exception;
        }
        try {
            // 成败与尝试历史统一落库；只有仍持有租约的执行者可以更新最终状态。
            tasks.finish(task, error);
        } finally {
            active.remove(task.getId());
            if (!closed) {
                timer.execute(this::safeScan);
            }
        }
    }

    /** 任务超时则中断；失去数据库执行权的旧线程也必须退出。 */
    private void pulse() {
        for (Execution execution : active.values()) {
            try {
                if (System.currentTimeMillis() - execution.started() > timeoutMs
                        || !tasks.heartbeat(execution.task())) {
                    if (execution.thread() != null) {
                        // 中断只能请求退出；发布处还会再次检查租约，阻止迟到请求生效。
                        execution.thread().interrupt();
                    }
                }
            } catch (Exception exception) {
                if (execution.thread() != null) {
                    execution.thread().interrupt();
                }
            }
        }
    }

    /** 停止领取并中断工作，遗留租约由下一实例恢复。 */
    @PreDestroy
    public void close() {
        closed = true;
        timer.shutdownNow();
        workers.shutdownNow();
    }
}
