package io.supportops.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.supportops.agent.service.DiagnosticEngine;
import io.supportops.agent.service.RunService;
import io.supportops.agent.service.impl.RunServiceImpl;
import io.supportops.agent.service.support.RunStreamSignals;
import io.supportops.feishu.FeishuProperties;
import io.supportops.feishu.dto.FeishuIncomingMessage;
import io.supportops.feishu.entity.FeishuOutboxEntity;
import io.supportops.feishu.mapper.FeishuBindingMapper;
import io.supportops.feishu.mapper.FeishuInboxMapper;
import io.supportops.feishu.mapper.FeishuOutboxMapper;
import io.supportops.feishu.service.FeishuService;
import io.supportops.feishu.service.impl.FeishuBindingServiceImpl;
import io.supportops.feishu.service.impl.FeishuServiceImpl;
import io.supportops.feishu.vo.FeishuViews;
import io.supportops.memory.service.impl.MemoryServiceImpl;
import io.supportops.user.mapper.UserMapper;
import io.supportops.user.service.UserService;
import io.supportops.user.service.impl.UserServiceImpl;
import io.supportops.user.vo.UserView;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/** 真实隔离 MySQL 验证飞书身份、去重、事务和投递恢复；执行器及平台发送为确定性夹具。 */
class FeishuPersistenceTest {
    private MapperTestDatabase database;
    private UserService users;
    private UserView owner;
    private FeishuBindingServiceImpl bindings;
    private RunServiceImpl runs;
    private FeishuService service;
    private final FeishuProperties properties = new FeishuProperties(true, "cli_fixture", "synthetic-secret", "tenant_fixture");
    private final AdjustableClock clock = new AdjustableClock();
    private final FixtureEngine engine = new FixtureEngine();
    private final List<SentMessage> sent = new CopyOnWriteArrayList<>();
    private final AtomicBoolean rejectSend = new AtomicBoolean();

    /** 每例创建随机数据库，所有核查使用独立 JDBC 查询。 */
    @BeforeEach
    void setup() throws Exception {
        database = new MapperTestDatabase();
        users = new UserServiceImpl(database.mapper(UserMapper.class), database.transactions);
        owner = users.create("feishu_owner", "synthetic-user-password", "USER");
        bindings = new FeishuBindingServiceImpl(database.mapper(FeishuBindingMapper.class), users, properties, clock);
        runs = newRuns();
        service = channel(runs, database.mapper(FeishuOutboxMapper.class));
    }

    /** 先中断任务再释放数据库，不遗留执行线程。 */
    @AfterEach
    void close() {
        engine.gate.countDown();
        runs.close();
        database.close();
    }

    /** 使用正式诊断生命周期，只有模型结果来自明确标记的执行夹具。 */
    private RunServiceImpl newRuns() {
        return new RunServiceImpl(database.runs, database.events, database.transactions, new ObjectMapper(),
                engine, new MemoryServiceImpl(database.memories), new RunStreamSignals(), 30);
    }

    /** 平台发送夹具记录固定请求，可模拟响应丢失而不改写消息标识。 */
    private FeishuService channel(RunService runService, FeishuOutboxMapper replies) {
        return new FeishuServiceImpl(database.mapper(FeishuInboxMapper.class), replies, bindings, runService,
                (openId, text, deliveryId) -> {
                    sent.add(new SentMessage(openId, text, deliveryId));
                    if (rejectSend.get()) {
                        throw new IllegalStateException("synthetic-lost-response");
                    }
                    return "om_reply_" + sent.size();
                }, database.transactions, properties, clock);
    }

    /** 生成平台文本私聊事件，时间来自同一可控制时钟。 */
    private FeishuIncomingMessage message(String id, String openId, String text) {
        return new FeishuIncomingMessage(properties.appId(), properties.tenantKey(), id, openId,
                "user", "p2p", "text", text, clock.millis());
    }

    /** 经正式收件事务消费绑定码，随后送出绑定成功提示。 */
    private String bind() {
        FeishuViews.BindingCode code = bindings.issue(owner.id());
        service.receive(message("om_bind", "ou_owner", code.command()));
        service.process();
        sent.clear();
        return code.id();
    }

    /** 一个码只能绑定一个身份，明文命令不进入收件、绑定或管理查询。 */
    @Test
    void bindingCodesAreScopedExpiringSingleUseAndNeverStoredInPlaintext() throws Exception {
        FeishuViews.BindingCode code = bindings.issue(owner.id());
        String raw = code.command().substring(3);
        assertThat(database.jdbc.queryForObject("SELECT code_hash FROM feishu_bindings WHERE id=?", String.class, code.id()))
                .hasSize(64).isNotEqualTo(raw);
        assertThat(code.toString()).doesNotContain(raw);
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Future<?> first = workers.submit(() -> service.receive(message("om_bind_a", "ou_first", code.command())));
            Future<?> second = workers.submit(() -> service.receive(message("om_bind_b", "ou_second", code.command())));
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
        assertThat(database.jdbc.queryForObject("SELECT COUNT(*) FROM feishu_bindings WHERE open_id IS NOT NULL", Integer.class)).isEqualTo(1);
        assertThat(database.jdbc.queryForObject("SELECT code_hash FROM feishu_bindings WHERE id=?", String.class, code.id())).isNull();
        assertThat(database.jdbc.queryForList("SELECT question FROM feishu_inbox", String.class)).containsOnly("");
        assertThat(new ObjectMapper().writeValueAsString(service.messages())).doesNotContain(raw, "codeHash");
        FeishuViews.BindingCode expired = bindings.issue(owner.id());
        clock.advance(600_001);
        service.receive(message("om_expired", "ou_expired", expired.command()));
        assertThat(bindings.find("ou_expired")).isNull();
        assertThat(engine.calls).hasValue(0);
    }

    /** 绑定变更、收件和回复必须同时提交；注入回复写入故障后全部回滚。 */
    @Test
    void bindingAndInboxRollbackWhenReplyPersistenceFails() {
        FeishuViews.BindingCode code = bindings.issue(owner.id());
        FeishuOutboxMapper failing = MapperTestDatabase.intercept(FeishuOutboxMapper.class,
                database.mapper(FeishuOutboxMapper.class), (method, arguments) -> {
                    if (method.getName().equals("insert")) {
                        throw new IllegalStateException("SYNTHETIC_OUTBOX_FAILURE");
                    }
                });
        FeishuService broken = channel(runs, failing);
        assertThatThrownBy(() -> broken.receive(message("om_rollback", "ou_owner", code.command())))
                .isInstanceOf(IllegalStateException.class);
        assertThat(database.jdbc.queryForObject("SELECT open_id FROM feishu_bindings WHERE id=?", String.class, code.id())).isNull();
        assertThat(database.jdbc.queryForObject("SELECT COUNT(*) FROM feishu_inbox", Integer.class)).isZero();
        service.receive(message("om_rollback", "ou_owner", code.command()));
        assertThat(bindings.find("ou_owner").getUserId()).isEqualTo(owner.id());
    }

    /** 群聊、其他企业、机器人和过期消息均不进入收件，未绑定用户不能调用模型。 */
    @Test
    void untrustedScopesAndUnboundUsersCannotStartDiagnosis() {
        bind();
        for (FeishuIncomingMessage incoming : List.of(
                new FeishuIncomingMessage("other_app", properties.tenantKey(), "om_a", "ou_owner", "user", "p2p", "text", "问题", clock.millis()),
                new FeishuIncomingMessage(properties.appId(), "other_tenant", "om_b", "ou_owner", "user", "p2p", "text", "问题", clock.millis()),
                new FeishuIncomingMessage(properties.appId(), properties.tenantKey(), "om_c", "ou_owner", "user", "group", "text", "问题", clock.millis()),
                new FeishuIncomingMessage(properties.appId(), properties.tenantKey(), "om_d", "ou_owner", "app", "p2p", "text", "问题", clock.millis()),
                new FeishuIncomingMessage(properties.appId(), properties.tenantKey(), "om_e", "ou_owner", "user", "p2p", "text", "问题", clock.millis() - 1_800_001))) {
            service.receive(incoming);
        }
        assertThat(database.jdbc.queryForObject("SELECT COUNT(*) FROM feishu_inbox", Integer.class)).isEqualTo(1);
        service.receive(message("om_unknown", "ou_unknown", "不应进入模型"));
        service.process();
        assertThat(engine.calls).hasValue(0);
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst().text()).contains("绑定").doesNotContain("不应进入模型");
    }

    /** 并发重投只写入一条收件；任务创建后回执落库失败也不重复调用模型。 */
    @Test
    void duplicateMessagesAndAdmissionCrashReuseOneOwnedRun() throws Exception {
        bind();
        FeishuIncomingMessage incoming = message("om_question", "ou_owner", "合成诊断问题");
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Future<?> first = workers.submit(() -> service.receive(incoming));
            Future<?> second = workers.submit(() -> service.receive(incoming));
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
        assertThat(database.jdbc.queryForObject("SELECT COUNT(*) FROM feishu_inbox WHERE message_id='om_question'", Integer.class)).isEqualTo(1);
        RunServiceImpl interrupted = spy(runs);
        AtomicBoolean once = new AtomicBoolean(true);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (once.getAndSet(false)) {
                throw new IllegalStateException("SYNTHETIC_CRASH_AFTER_ADMISSION");
            }
            return result;
        }).when(interrupted).startExternal(anyString(), anyString(), any(UserView.class));
        FeishuService recovering = channel(interrupted, database.mapper(FeishuOutboxMapper.class));
        recovering.process();
        String id = database.jdbc.queryForObject("SELECT id FROM feishu_inbox WHERE message_id='om_question'", String.class);
        assertThat(database.jdbc.queryForObject("SELECT state FROM feishu_inbox WHERE id=?", String.class, id)).isEqualTo("RECEIVED");
        waitUntil(() -> "COMPLETED".equals(runs.getOwned(id, owner.id()).status()));
        recovering.process();
        recovering.process();
        assertThat(engine.calls).hasValue(1);
        assertThat(database.jdbc.queryForObject("SELECT user_id FROM runs WHERE id=?", String.class, id)).isEqualTo(owner.id());
        assertThat(database.jdbc.queryForObject("SELECT COUNT(*) FROM runs", Integer.class)).isEqualTo(1);
        assertThat(engine.contexts).extracting(DiagnosticEngine.Context::lexicalOnly).containsExactly(false);
        assertThat(database.jdbc.queryForObject("SELECT input_tokens FROM runs WHERE id=?", Long.class, id)).isEqualTo(7);
        assertThat(sent).hasSize(2).allMatch(item -> item.openId().equals("ou_owner"));
        assertThat(sent.getLast().text()).contains("诊断完成", "合成证据", id);
        UserView other = users.create("other_admin", "synthetic-admin-password", "ADMIN");
        assertThatThrownBy(() -> runs.getOwned(id, other.id())).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> runs.startExternal(id, "替换请求正文", owner)).isInstanceOf(ResponseStatusException.class);
    }

    /** 同时到来的第二条问题必须明确忙碌，不承诺不存在的排队能力。 */
    @Test
    void busyAndRevokedBindingKeepExistingAdmissionBoundary() throws Exception {
        String bindingId = bind();
        engine.gate = new CountDownLatch(1);
        service.receive(message("om_active", "ou_owner", "第一个问题"));
        service.process();
        assertThat(engine.entered.await(5, TimeUnit.SECONDS)).isTrue();
        service.receive(message("om_busy", "ou_owner", "第二个问题"));
        service.process();
        assertThat(database.jdbc.queryForObject("SELECT error_code FROM feishu_inbox WHERE message_id='om_busy'", String.class))
                .isEqualTo("DIAGNOSIS_IN_PROGRESS");
        assertThat(sent).anyMatch(item -> item.text().contains("本次未受理"));
        bindings.revoke(bindingId);
        bindings.revoke(bindingId);
        engine.gate.countDown();
        String id = database.jdbc.queryForObject("SELECT id FROM feishu_inbox WHERE message_id='om_active'", String.class);
        waitUntil(() -> "COMPLETED".equals(runs.getOwned(id, owner.id()).status()));
        sent.clear();
        service.process();
        assertThat(sent).isEmpty();
        assertThat(database.jdbc.queryForObject("SELECT state FROM feishu_inbox WHERE id=?", String.class, id)).isEqualTo("BLOCKED");
        assertThat(engine.calls).hasValue(1);
    }

    /** 回应丢失后重试冻结正文和 uuid；即使原任务内容变化也不重新生成回复。 */
    @Test
    void lostSendResponseRetriesFrozenReplyAndStopsOutsideDedupWindow() throws Exception {
        bind();
        engine.answer = "观测时间：2026-09-22T10:29:05Z。依据：合成证据。";
        service.receive(message("om_result", "ou_owner", "合成恢复问题"));
        service.process();
        String id = database.jdbc.queryForObject("SELECT id FROM feishu_inbox WHERE message_id='om_result'", String.class);
        waitUntil(() -> "COMPLETED".equals(runs.getOwned(id, owner.id()).status()));
        assertThat(database.jdbc.queryForObject("SELECT answer FROM runs WHERE id=?", String.class, id))
                .contains("2026-09-22T10:29:05Z");
        rejectSend.set(true);
        service.process();
        SentMessage firstAttempt = sent.getLast();
        assertThat(firstAttempt.text()).contains("诊断完成");
        assertThat(firstAttempt.text()).contains("2026-09-22 18:29:05（北京时间）")
                .doesNotContain("2026-09-22T10:29:05Z");
        database.jdbc.update("UPDATE runs SET answer='合成后续改变' WHERE id=?", id);
        clock.advance(20_000);
        rejectSend.set(false);
        service = channel(runs, database.mapper(FeishuOutboxMapper.class));
        service.process();
        assertThat(sent.getLast()).isEqualTo(firstAttempt);
        assertThat(database.jdbc.queryForObject("SELECT state FROM feishu_outbox WHERE id=?", String.class, firstAttempt.id())).isEqualTo("SENT");
        assertThat(engine.calls).hasValue(1);
        // 模拟平台已收到但本地确认未提交，且服务停机超过平台去重时间。
        database.jdbc.update("UPDATE feishu_outbox SET state='PENDING', next_attempt_at=0 WHERE id=?", firstAttempt.id());
        int sends = sent.size();
        clock.advance(3_600_001);
        service.process();
        assertThat(sent).hasSize(sends);
        assertThat(database.jdbc.queryForObject("SELECT state FROM feishu_outbox WHERE id=?", String.class, firstAttempt.id())).isEqualTo("UNKNOWN");
    }

    /** 进程关闭后的活动任务保持中断，只补发终态，不在恢复扫描中重跑模型。 */
    @Test
    void restartReportsInterruptedRunWithoutExecutingAgain() throws Exception {
        bind();
        engine.gate = new CountDownLatch(1);
        service.receive(message("om_restart", "ou_owner", "合成中断问题"));
        service.process();
        assertThat(engine.entered.await(5, TimeUnit.SECONDS)).isTrue();
        String id = database.jdbc.queryForObject("SELECT id FROM feishu_inbox WHERE message_id='om_restart'", String.class);
        runs.close();
        runs = newRuns();
        service = channel(runs, database.mapper(FeishuOutboxMapper.class));
        service.process();
        assertThat(runs.getOwned(id, owner.id()).status()).isEqualTo("INTERRUPTED");
        assertThat(engine.calls).hasValue(1);
        assertThat(sent.getLast().text()).contains("诊断已中断");
    }

    /** 有限等待真实异步任务，不依赖不确定的模型自然语言。 */
    private void waitUntil(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    /** 保留发送请求的值快照以比较重试是否改变请求。 */
    private record SentMessage(String openId, String text, String id) {}

    /** 可推进业务时钟，不通过真实长时间等待测试到期与恢复边界。 */
    private static class AdjustableClock extends Clock {
        private long now = Instant.parse("2026-09-22T00:00:00Z").toEpochMilli();
        /** 推进业务时间。 */
        void advance(long millis) { now += millis; }
        /** 所有持久化时刻使用 UTC。 */
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        /** 测试仅使用 UTC，不更换时间源。 */
        @Override public Clock withZone(ZoneId zone) { return this; }
        /** 返回可控制的当前时刻。 */
        @Override public Instant instant() { return Instant.ofEpochMilli(now); }
    }

    /** 仅验证生命周期和计量，不作为真实模型效果证据。 */
    private static class FixtureEngine implements DiagnosticEngine {
        private final AtomicInteger calls = new AtomicInteger();
        private final List<Context> contexts = new CopyOnWriteArrayList<>();
        private final CountDownLatch entered = new CountDownLatch(1);
        private volatile CountDownLatch gate = new CountDownLatch(0);
        private volatile String answer = "结论：合成结果。依据：合成证据。缺失材料：待补日志。建议：核对环境。";
        /** 夹具始终就绪。 */
        @Override public boolean configured() { return true; }
        /** 明确标注为测试执行器。 */
        @Override public String modelName() { return "feishu-lifecycle-fixture"; }
        /** 发出实际持久化用量事件并等待测试释放，响应取消中断。 */
        @Override
        public Result execute(Context context, BiConsumer<String, Object> events) {
            contexts.add(context);
            calls.incrementAndGet();
            events.accept("USAGE", Map.of("inputTokens", 7, "outputTokens", 3));
            entered.countDown();
            try {
                gate.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("FIXTURE_INTERRUPTED");
            }
            return new Result(answer, 7, 3, false);
        }
    }
}
