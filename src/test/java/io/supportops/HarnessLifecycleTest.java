package io.supportops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.supportops.agent.service.DiagnosticEngine;
import io.supportops.agent.service.DiagnosticEngine.Context;
import io.supportops.agent.service.DiagnosticEngine.Result;
import io.supportops.agent.service.RunService;
import io.supportops.agent.service.impl.RunServiceImpl;
import io.supportops.agent.service.impl.UsageServiceImpl;
import io.supportops.agent.vo.UsageAnalysis;
import io.supportops.agent.service.support.RunStreamSignals;
import io.supportops.agent.vo.DiagnosisRun;
import io.supportops.memory.service.MemoryService;
import io.supportops.memory.service.impl.MemoryServiceImpl;
import io.supportops.persistence.MapperTestDatabase;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/** 使用真实隔离数据库验证诊断中断、终态与用量持久化。 */
class HarnessLifecycleTest {
    JdbcTemplate db;
    MemoryService memory;

    /** 报告固定用量后等待中断的确定性执行夹具，不模拟模型诊断效果。 */
    static class BlockingEngine implements DiagnosticEngine {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch exited = new CountDownLatch(1);
        boolean configured = true;

        /** 检查本地配置是否满足执行要求，不发起远程模型请求。 */
        public boolean configured() {
            return configured;
        }

        /** 返回当前选择的对话模型名称。 */
        public String modelName() {
            return "test-only-blocking-engine";
        }

        /** 发送用量事件并等待，用于制造可取消与超时的执行窗口。 */
        public Result execute(Context context, BiConsumer<String, Object> events) {
            events.accept("USAGE", Map.of("inputTokens", 12, "outputTokens", 3));
            entered.countDown();
            try {
                Thread.sleep(10000);
                return new Result("unexpected completion", 12, 3, false);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted test fixture");
            } finally {
                exited.countDown();
            }
        }
    }

    MapperTestDatabase database;
    final List<RunServiceImpl> harnesses = new ArrayList<>();

    /** 使用真实 MyBatis-Plus 与隔离数据库准备生命周期测试。 */
    @BeforeEach
    void setup() throws Exception {
        database = new MapperTestDatabase();
        db = database.jdbc;
        memory = new MemoryServiceImpl(database.memories);
    }

    /** 创建可独立恢复遗留任务的诊断服务。 */
    RunService harness(DiagnosticEngine engine, long timeout) {
        RunServiceImpl service =
                new RunServiceImpl(
                        database.runs,
                        database.events,
                        database.transactions,
                        new ObjectMapper(),
                        engine,
                        memory,
                        new RunStreamSignals(),
                        timeout);
        harnesses.add(service);
        return service;
    }

    /** 测试结束时关闭执行资源，再释放数据库。 */
    @AfterEach
    void cleanup() {
        harnesses.forEach(RunServiceImpl::close);
        database.close();
    }

    /** 验证主动取消后迟到异常不覆盖终态，已报告用量保留。 */
    @Test
    void cancellationSurvivesLateErrorAndPreservesUsage() throws Exception {
        BlockingEngine engine = new BlockingEngine();
        RunService harness = harness(engine, 30);
        DiagnosisRun run = harness.start("cancel test", null, true);
        assertThat(engine.entered.await(2, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> harness.archive(run.id())).hasMessageContaining("DIAGNOSIS_IN_PROGRESS");
        assertThatThrownBy(harness::requireIdle).hasMessageContaining("DIAGNOSIS_IN_PROGRESS");
        assertThat(harness.cancel(run.id()).status()).isEqualTo("CANCELLED");
        assertThat(engine.exited.await(2, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(30);
        assertThat(harness.get(run.id()).status()).isEqualTo("CANCELLED");
        assertThat(harness.get(run.id()).inputTokens()).isEqualTo(12);
        assertThat(harness.cancel(run.id()).status()).isEqualTo("CANCELLED");
        harness.requireIdle();
    }

    /** 整个会话逻辑归档、重复归档与重启读取均不减少历史问答和统计。 */
    @Test
    void archivePreservesConversationEvidenceAndUsage() {
        RunService harness = harness(new BlockingEngine(), 30);
        String session = UUID.randomUUID().toString();
        for (int index = 0; index < 2; index++) {
            db.update("INSERT INTO runs(id,session_id,question,status,answer,error_code,created_at,input_tokens,output_tokens) "
                    + "VALUES(?,?,?,'COMPLETED','合成回答','','2026-09-08T00:00:00Z',10,5)",
                    "archive-" + index, session, "合成问题" + index);
        }
        db.update("INSERT INTO run_events(run_id,kind,content,created_at) VALUES('archive-0','USAGE','{}','2026-09-08T00:00:00Z')");
        UsageServiceImpl usage = new UsageServiceImpl(database.runs);
        UsageAnalysis before = usage.analyze("2026-09-08", "2026-09-08", "hour");
        assertThat(harness.list()).hasSize(2);
        harness.archive("archive-0");
        String archivedAt = db.queryForObject("SELECT archived_at FROM runs WHERE id='archive-0'", String.class);
        assertThat(archivedAt).isNotBlank();
        harness.archive("archive-1");
        assertThat(db.queryForList("SELECT DISTINCT archived_at FROM runs", String.class)).containsExactly(archivedAt);
        assertThat(harness.list()).isEmpty();
        assertThat(harness.get("archive-0").answer()).isEqualTo("合成回答");
        assertThat(harness.events("archive-0", 0)).hasSize(1);
        assertThat(usage.analyze("2026-09-08", "2026-09-08", "hour")).isEqualTo(before);
        assertThat(usage.session(session, 0)).hasSize(2);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM runs", Long.class)).isEqualTo(2);
        assertThatThrownBy(() -> harness.start("归档后续聊", session, true)).hasMessageContaining("SESSION_ARCHIVED");
        assertThat(harness(new BlockingEngine(), 30).list()).isEmpty();
    }

    /** 验证时间预算耗尽会中断执行并保存超时结果。 */
    @Test
    void timeBudgetInterruptsWorkAndPersistsTimeout() throws Exception {
        BlockingEngine engine = new BlockingEngine();
        RunService harness = harness(engine, 1);
        DiagnosisRun run = harness.start("timeout test", null, true);
        assertThat(engine.exited.await(3, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(30);
        assertThat(harness.get(run.id()).status()).isEqualTo("TIMED_OUT");
        assertThat(harness.get(run.id()).answer()).isEmpty();
        assertThat(harness.get(run.id()).errorCode()).isEqualTo("TIME_BUDGET_EXCEEDED");
    }

    /** 验证进程恢复只标记旧任务中断，不自动重放诊断。 */
    @Test
    void interruptedTasksAreNotReplayedOnProcessStart() {
        String id = UUID.randomUUID().toString();
        db.update(
                "INSERT INTO runs(id,session_id,question,status,answer,error_code,created_at)"
                        + " VALUES (?,?,?,'RUNNING','','',?)",
                id,
                UUID.randomUUID().toString(),
                "pending",
                "2026-01-01T00:00:00Z");
        RunService harness = harness(new BlockingEngine(), 30);
        assertThat(harness.get(id).status()).isEqualTo("INTERRUPTED");
        assertThat(harness.get(id).errorCode()).isEqualTo("PROCESS_RESTARTED");
        harness.requireIdle();
    }

    /** 验证模型未配置时不创建任务或伪造成功结果。 */
    @Test
    void missingModelDoesNotCreateFakeSuccessOrRun() {
        BlockingEngine engine = new BlockingEngine();
        engine.configured = false;
        RunService harness = harness(engine, 30);
        assertThatThrownBy(() -> harness.start("question", null, true))
                .hasMessageContaining("MODEL_NOT_CONFIGURED");
        assertThat(harness.list()).isEmpty();
    }
}
