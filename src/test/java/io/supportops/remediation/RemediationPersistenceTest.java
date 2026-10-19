package io.supportops.remediation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.supportops.agent.service.RunService;
import io.supportops.agent.service.DiagnosticEngine;
import io.supportops.agent.service.impl.RunServiceImpl;
import io.supportops.agent.service.support.RunStreamSignals;
import io.supportops.agent.vo.DiagnosisRun;
import io.supportops.memory.service.MemoryService;
import io.supportops.demo.service.impl.DemoEnvironment;
import io.supportops.persistence.MapperTestDatabase;
import io.supportops.remediation.constant.RemediationContract;
import io.supportops.remediation.dto.RemediationRequests.Command;
import io.supportops.remediation.dto.RemediationRequests.Create;
import io.supportops.remediation.entity.RemediationEventEntity;
import io.supportops.remediation.mapper.RemediationDecisionMapper;
import io.supportops.remediation.mapper.RemediationEventMapper;
import io.supportops.remediation.mapper.RemediationExecutionMapper;
import io.supportops.remediation.mapper.RemediationPlanMapper;
import io.supportops.remediation.mapper.RemediationRequestMapper;
import io.supportops.remediation.service.impl.RemediationServiceImpl;
import io.supportops.remediation.service.support.RemediationJson;
import io.supportops.remediation.service.support.RemediationProcessIdentity;
import io.supportops.remediation.vo.RemediationViews.Detail;
import io.supportops.remediation.vo.RemediationViews.Receipt;
import io.supportops.remediation.vo.RemediationViews.Snapshot;
import io.supportops.user.CurrentUser;
import io.supportops.user.mapper.UserMapper;
import io.supportops.user.service.UserService;
import io.supportops.user.service.impl.UserServiceImpl;
import io.supportops.user.vo.UserView;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.io.OutputStream;
import java.io.IOException;
import java.util.function.Supplier;
import java.util.function.BiConsumer;

/** 真实隔离 MySQL、正式 Mapper、真实回环下游；故障钩子只在测试类存在。 */
class RemediationPersistenceTest {
    private static MapperTestDatabase database;
    private static UserService users;
    private static UserView admin;
    private TestEnvironment environment;
    private RemediationServiceImpl service;
    private RunService runs;

    /** 创建一次随机数据库，账号真实保存且不访问日常工作台。 */
    @BeforeAll
    static void database() throws Exception {
        database = new MapperTestDatabase();
        users = new UserServiceImpl(database.mapper(UserMapper.class), database.transactions);
        admin = users.setup("remediation_admin", "synthetic-remediation-password");
    }

    /** 每个用例使用新合成目标，只清理本组随机数据库的修复记录。 */
    @BeforeEach
    void setup() throws Exception {
        for (String table : List.of("remediation_events", "remediation_requests", "remediation_executions", "remediation_decisions", "remediation_plans")) {
            database.jdbc.update("DELETE FROM " + table);
        }
        environment = new TestEnvironment();
        runs = mock(RunService.class);
        when(runs.whenIdle(org.mockito.ArgumentMatchers.<Supplier<Detail>>any())).thenAnswer(call -> {
            Supplier<Detail> operation = call.getArgument(0);
            synchronized (runs) {
                return operation.get();
            }
        });
        service = service(database.mapper(RemediationEventMapper.class), database.mapper(RemediationExecutionMapper.class));
        identity(admin);
    }

    /** 释放本例下游及请求上下文。 */
    @AfterEach
    void closeEnvironment() {
        RequestContextHolder.resetRequestAttributes();
        ReflectionTestUtils.invokeMethod(environment, "close");
    }

    /** 随机资源由原隔离夹具核对名称并清理。 */
    @AfterAll
    static void closeDatabase() {
        database.close();
    }

    /** 构造正式服务，允许仅在 Mapper 调用前注入事务故障。 */
    private RemediationServiceImpl service(RemediationEventMapper audit, RemediationExecutionMapper executions) {
        return new RemediationServiceImpl(database.mapper(RemediationPlanMapper.class),
                database.mapper(RemediationDecisionMapper.class), executions, database.mapper(RemediationRequestMapper.class),
                audit, database.transactions, environment, runs, users, new RemediationJson(), new RemediationProcessIdentity());
    }

    /** 服务端认证属性来自真实数据库账号。 */
    private static void identity(UserView user) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(CurrentUser.ATTRIBUTE, user);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    /** 创建当前现场不可变提案。 */
    private Detail proposal() {
        return service.create(new Create(uuid(), RemediationContract.ACTION, "固定迁移", "合成环境回归", null, null));
    }

    /** 从当前版本生成独立固定命令。 */
    private Command command(Detail detail) {
        return new Command(uuid(), detail.version(), detail.planHash(), null);
    }

    /** 批准仍没有副作用。 */
    private Detail approved() {
        Detail plan = proposal();
        return service.approve(plan.id(), command(plan));
    }

    /** 测试 UUID 不能与其它用例混用。 */
    private static String uuid() {
        return UUID.randomUUID().toString();
    }

    /** R01/R02/R08/R20：预检查无写，完整闭环一次应用一次HTTP，重放和报告只读。 */
    @Test
    void previewApprovalExecutionAndReplayUseActualHttp() {
        int logs = environment.logs().entries().size();
        Snapshot before = environment.snapshot();
        service.preview(RemediationContract.ACTION);
        Detail approved = approved();
        assertThat(environment.snapshot().revision()).isEqualTo(before.revision());
        assertThat(environment.logs().entries()).hasSize(logs);
        assertThat(approved.decision().actorId()).isEqualTo(admin.id());
        Command execute = command(approved);
        Detail finished = service.execute(approved.id(), execute);
        assertThat(finished.execution().status()).isEqualTo("SUCCEEDED");
        assertThat(finished.execution().applicationStatus()).isEqualTo("APPLIED");
        assertThat(finished.execution().verificationStatus()).isEqualTo("PASSED");
        assertThat(finished.execution().receipt().verification().httpStatus()).isEqualTo(200);
        assertThat(finished.execution().receipt().verification().accepted()).isTrue();
        assertThat(service.execute(approved.id(), execute).execution().id()).isEqualTo(finished.execution().id());
        assertThat(environment.applications).isEqualTo(1);
        assertThat(environment.verifications).isEqualTo(1);
        assertThat(database.jdbc.queryForObject("SELECT COUNT(*) FROM remediation_executions", Integer.class)).isEqualTo(1);
        assertThat(database.jdbc.queryForObject("SELECT COUNT(*) FROM remediation_events", Integer.class)).isEqualTo(4);
        assertThat(service.report(approved.id()).events()).hasSize(4);
        assertThat(service.request(execute.requestId()).executionId()).isEqualTo(finished.execution().id());
    }

    /** R03：实际字段不适用时拒绝；未知版本也不能创建。 */
    @Test
    void unsuitableAndUnknownVersionNeverApply() {
        for (String scenario : List.of("healthy", "credentials", "unavailable", "insufficient")) {
            environment.configure(scenario);
            assertThat(service.preview(RemediationContract.ACTION).before().applicable()).isFalse();
            assertThatThrownBy(this::proposal).hasMessageContaining("REMEDIATION_ACTION_NOT_APPLICABLE");
        }
        environment.configure("migration");
        ReflectionTestUtils.setField(environment, "version", "3.0");
        assertThatThrownBy(this::proposal).hasMessageContaining("REMEDIATION_ACTION_NOT_APPLICABLE");
        assertThat(environment.applications).isZero();
    }

    /** R05/R06/R09：摘要、版本、同名重置与请求意图都绑定，拒绝结果稳定。 */
    @Test
    void bindingDriftAndRequestConflictsAreStable() {
        Detail plan = proposal();
        assertThatThrownBy(() -> service.approve(plan.id(), new Command(uuid(), plan.version(), "a".repeat(64), null)))
                .hasMessageContaining("REMEDIATION_PLAN_HASH_CONFLICT");
        assertThatThrownBy(() -> service.approve(plan.id(), new Command(uuid(), 99, plan.planHash(), null)))
                .hasMessageContaining("REMEDIATION_VERSION_CONFLICT");
        Command approve = command(plan);
        environment.configure("migration");
        assertThatThrownBy(() -> service.approve(plan.id(), approve)).hasMessageContaining("REMEDIATION_ENVIRONMENT_CHANGED");
        assertThat(service.get(plan.id()).status()).isEqualTo("STALE");
        assertThatThrownBy(() -> service.approve(plan.id(), approve)).hasMessageContaining("REMEDIATION_ENVIRONMENT_CHANGED");
        assertThatThrownBy(() -> service.cancel(plan.id(), approve)).hasMessageContaining("REMEDIATION_REQUEST_CONFLICT");
        assertThat(environment.applications).isZero();
    }

    /** R07：拒绝、撤销与过期不能消费，审批没有自动执行。 */
    @Test
    void rejectionCancellationAndExpiryAreFinal() {
        Detail rejected = proposal();
        rejected = service.reject(rejected.id(), new Command(uuid(), rejected.version(), rejected.planHash(), "证据不足"));
        Detail finalRejected = rejected;
        assertThatThrownBy(() -> service.approve(finalRejected.id(), command(finalRejected))).hasMessageContaining("REMEDIATION_STATE_CONFLICT");
        Detail cancelled = approved();
        cancelled = service.cancel(cancelled.id(), command(cancelled));
        Detail finalCancelled = cancelled;
        assertThatThrownBy(() -> service.execute(finalCancelled.id(), command(finalCancelled))).hasMessageContaining("REMEDIATION_STATE_CONFLICT");
        Detail expired = proposal();
        database.jdbc.update("UPDATE remediation_plans SET expires_at=0 WHERE id=?", expired.id());
        assertThatThrownBy(() -> service.approve(expired.id(), command(expired))).hasMessageContaining("REMEDIATION_PLAN_EXPIRED");
        Detail window = approved();
        database.jdbc.update("UPDATE remediation_decisions SET expires_at=0 WHERE plan_id=?", window.id());
        assertThatThrownBy(() -> service.execute(window.id(), command(window))).hasMessageContaining("REMEDIATION_APPROVAL_EXPIRED");
        assertThat(environment.applications).isZero();
    }

    /** R06：配置改回仍有修订差异，换实例后旧批准不能复活。 */
    @Test
    void restoredConfigurationAndRestartInvalidateOriginalApproval() throws Exception {
        Detail first = approved();
        environment.configure("healthy");
        environment.configure("migration");
        assertThat(environment.snapshot().provided()).isEqualTo(first.plan().before().provided());
        assertThatThrownBy(() -> service.execute(first.id(), command(first))).hasMessageContaining("REMEDIATION_ENVIRONMENT_CHANGED");
        Detail second = approved();
        ReflectionTestUtils.invokeMethod(environment, "close");
        environment = new TestEnvironment();
        service = service(database.mapper(RemediationEventMapper.class), database.mapper(RemediationExecutionMapper.class));
        service.recover();
        Detail stale = service.get(second.id());
        assertThat(stale.status()).isEqualTo("STALE");
        assertThatThrownBy(() -> service.execute(second.id(), command(second))).hasMessageContaining("REMEDIATION_VERSION_CONFLICT");
        assertThatThrownBy(() -> service.execute(stale.id(), command(stale))).hasMessageContaining("REMEDIATION_STATE_CONFLICT");
        assertThat(environment.applications).isZero();
        assertThat(environment.verifications).isZero();
    }

    /** R07：批准与驳回、撤销与执行分别竞争，只有唯一状态转换可以提交。 */
    @Test
    void competingDecisionsAndCancellationHaveOneWinner() throws Exception {
        Detail plan = proposal();
        List<String> decisions = compete(() -> service.approve(plan.id(), command(plan)),
                () -> service.reject(plan.id(), new Command(uuid(), plan.version(), plan.planHash(), "并发驳回")));
        assertThat(decisions).filteredOn(value -> !value.contains("CONFLICT")).hasSize(1);
        assertThat(database.jdbc.queryForObject("SELECT COUNT(*) FROM remediation_decisions WHERE plan_id=?", Integer.class, plan.id())).isEqualTo(1);
        assertThat(database.jdbc.queryForObject("SELECT COUNT(*) FROM remediation_events WHERE plan_id=? AND kind IN ('APPROVE','REJECT')", Integer.class, plan.id())).isEqualTo(1);
        Detail approved = approved();
        List<String> race = compete(() -> service.cancel(approved.id(), command(approved)),
                () -> service.execute(approved.id(), command(approved)));
        assertThat(race).filteredOn(value -> !value.contains("CONFLICT")).hasSize(1);
        Detail result = service.get(approved.id());
        int expected = result.status().equals("CANCELLED") ? 0 : 1;
        assertThat(environment.applications).isEqualTo(expected);
        assertThat(environment.verifications).isEqualTo(expected);
        assertThat(database.jdbc.queryForObject("SELECT COUNT(*) FROM remediation_executions", Integer.class)).isEqualTo(expected);
    }

    /** 为状态竞争同步两个真实请求上下文，不隐藏胜者以外的异常。 */
    private List<String> compete(Supplier<Detail> first, Supplier<Detail> second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<String>> results = List.of(executor.submit(() -> competingResult(first, start)),
                    executor.submit(() -> competingResult(second, start)));
            start.countDown();
            return List.of(results.get(0).get(10, TimeUnit.SECONDS), results.get(1).get(10, TimeUnit.SECONDS));
        }
    }

    /** 并发失败只接受状态或版本冲突，其他失败继续使测试失败。 */
    private String competingResult(Supplier<Detail> operation, CountDownLatch start) throws Exception {
        identity(admin);
        try {
            start.await();
            return operation.get().status();
        } catch (ResponseStatusException exception) {
            assertThat(exception.getReason()).contains("CONFLICT");
            return exception.getReason();
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    /** R18：分页顺序与独立 SQL 一致，同一提案的决策唯一约束不能被 Mapper 绕开。 */
    @Test
    void pagingAndDatabaseUniquenessUseActualRows() {
        for (int count = 0; count < 5; count++) {
            approved();
        }
        List<String> order = database.jdbc.queryForList("SELECT id FROM remediation_plans ORDER BY created_at DESC,id DESC", String.class);
        assertThat(service.list(null, 0, 2).items().stream().map(Detail::id)).containsExactlyElementsOf(order.subList(0, 2));
        assertThat(service.list(null, 1, 2).items().stream().map(Detail::id)).containsExactlyElementsOf(order.subList(2, 4));
        assertThat(service.list("APPROVED", 0, 2).total()).isEqualTo(5);
        assertThatThrownBy(() -> database.jdbc.update("INSERT INTO remediation_decisions SELECT ?,plan_id,plan_hash,kind,actor_id,reason,expires_at,created_at FROM remediation_decisions LIMIT 1", uuid()))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(database.jdbc.queryForObject("SELECT COUNT(*) FROM remediation_decisions", Integer.class)).isEqualTo(5);
    }

    /** R05：审批后撤权，重新查询审批者权限。 */
    @Test
    void revokedApproverCannotAuthorizeExecution() {
        UserView approver = users.create("second_admin", "synthetic-second-password", "ADMIN");
        Detail plan = proposal();
        identity(approver);
        Detail approved = service.approve(plan.id(), command(plan));
        database.jdbc.update("UPDATE app_users SET role='USER' WHERE id=?", approver.id());
        identity(admin);
        assertThatThrownBy(() -> service.execute(approved.id(), command(approved))).hasMessageContaining("ADMIN_REQUIRED");
        assertThat(environment.applications).isZero();
    }

    /** R08：不同请求并发只能一次执行，数据库行数独立核对。 */
    @Test
    void concurrentRequestsConsumeOnlyOneApproval() throws Exception {
        Detail approved = approved();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Supplier<String> task = () -> {
                identity(admin);
                try {
                    start.await();
                    return service.execute(approved.id(), command(approved)).execution().status();
                } catch (Exception exception) {
                    return exception.getMessage();
                } finally {
                    RequestContextHolder.resetRequestAttributes();
                }
            };
            Future<String> first = executor.submit(task::get);
            Future<String> second = executor.submit(task::get);
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .anyMatch(value -> value.equals("SUCCEEDED")).anyMatch(value -> value.contains("CONFLICT"));
        }
        assertThat(environment.applications).isEqualTo(1);
        assertThat(environment.verifications).isEqualTo(1);
        assertThat(database.jdbc.queryForObject("SELECT COUNT(*) FROM remediation_executions", Integer.class)).isEqualTo(1);
    }

    /** R11：应用后真实401/503/accepted=false，不能改成未修改或自动回滚。 */
    @Test
    void appliedVerificationFailuresStayApplied() {
        for (int httpStatus : List.of(401, 503, 200)) {
            environment.configure("migration");
            environment.responseStatus = httpStatus;
            environment.accepted = false;
            Detail approved = approved();
            Detail result = service.execute(approved.id(), command(approved));
            assertThat(result.execution().status()).isEqualTo("VERIFICATION_FAILED");
            assertThat(result.execution().applicationStatus()).isEqualTo("APPLIED");
            assertThat(result.execution().receipt().verification().httpStatus()).isEqualTo(httpStatus);
            assertThat(environment.config().provided()).containsEntry("sync.targetPath", "/v2/orders");
        }
    }

    /** R14：受理事务故障回滚所有记录，配置零写入。 */
    @Test
    void acceptanceTransactionFailureHasZeroExternalWrites() {
        Detail approved = approved();
        RemediationEventMapper failing = MapperTestDatabase.intercept(RemediationEventMapper.class,
                database.mapper(RemediationEventMapper.class), (method, args) -> {
                    if (method.getName().equals("insert") && args[0] instanceof RemediationEventEntity event && event.getKind().equals("ACCEPTED")) {
                        throw new IllegalStateException("synthetic-acceptance-fault");
                    }
                });
        service = service(failing, database.mapper(RemediationExecutionMapper.class));
        assertThatThrownBy(() -> service.execute(approved.id(), command(approved))).hasMessageContaining("synthetic-acceptance-fault");
        assertThat(environment.applications).isZero();
        assertThat(database.jdbc.queryForObject("SELECT COUNT(*) FROM remediation_executions", Integer.class)).isZero();
        assertThat(database.jdbc.queryForObject("SELECT status FROM remediation_plans WHERE id=?", String.class, approved.id())).isEqualTo("APPROVED");
    }

    /** R14/R16/R17：完成事务失败后只补录原回执，无第二次POST，终态不能迟到覆盖。 */
    @Test
    void completionFaultReconcilesOriginalReceiptWithoutNewHttp() {
        Detail approved = approved();
        AtomicBoolean fail = new AtomicBoolean(true);
        RemediationEventMapper failing = MapperTestDatabase.intercept(RemediationEventMapper.class,
                database.mapper(RemediationEventMapper.class), (method, args) -> {
                    if (method.getName().equals("insert") && args[0] instanceof RemediationEventEntity event
                            && event.getKind().equals("FINISHED") && fail.getAndSet(false)) {
                        throw new IllegalStateException("synthetic-completion-fault");
                    }
                });
        service = service(failing, database.mapper(RemediationExecutionMapper.class));
        Command execute = command(approved);
        assertThatThrownBy(() -> service.execute(approved.id(), execute)).hasMessageContaining("REMEDIATION_RESULT_NOT_PERSISTED");
        Detail pending = service.request(execute.requestId()).detail();
        assertThat(pending.execution().status()).isEqualTo("RUNNING");
        assertThat(database.jdbc.queryForObject("SELECT COUNT(*) FROM remediation_events WHERE kind='FINISHED'", Integer.class)).isZero();
        Detail result = service.reconcile(pending.execution().id(), command(pending));
        assertThat(result.execution().status()).isEqualTo("SUCCEEDED");
        assertThat(environment.applications).isEqualTo(1);
        assertThat(environment.verifications).isEqualTo(1);
        assertThatThrownBy(() -> service.reconcile(result.execution().id(), command(result))).hasMessageContaining("REMEDIATION_STATE_CONFLICT");
    }

    /** R12/R16：当前健康、历史成功与无原回执不能把未知执行变绿。 */
    @Test
    void missingOriginalReceiptStaysUnknownAndManualClosurePreservesFacts() {
        Detail approved = approved();
        environment.dropReceipt = true;
        Detail unknown = service.execute(approved.id(), command(approved));
        assertThat(unknown.execution().status()).isEqualTo("UNKNOWN");
        assertThat(environment.health().httpStatus()).isEqualTo(200);
        environment.synchronizeOrder();
        Detail checked = service.reconcile(unknown.execution().id(), command(unknown));
        assertThat(checked.execution().status()).isEqualTo("UNKNOWN");
        environment.configure("migration");
        assertThatThrownBy(this::proposal).hasMessageContaining("REMEDIATION_UNRESOLVED_BLOCKS_TARGET");
        Detail closed = service.closeUnknown(checked.execution().id(), new Command(uuid(), checked.version(), checked.planHash(), "原回执丢失，人工结束"));
        assertThat(closed.execution().status()).isEqualTo("CLOSED_UNRESOLVED");
        assertThat(closed.execution().applicationStatus()).isEqualTo("UNKNOWN");
        Detail replacement = service.create(new Create(uuid(), RemediationContract.ACTION, "重新提案", "重新采集", null, closed.execution().id()));
        assertThat(replacement.status()).isEqualTo("PENDING_APPROVAL");
        assertThat(replacement.plan().supersedesExecutionId()).isEqualTo(closed.execution().id());
    }

    /** R17：满容量不驱逐原回执，超大配置证据不静默截断。 */
    @Test
    void receiptCapacityAndEvidenceBudgetsRejectBeforeApplication() {
        for (int i = 0; i < RemediationContract.RECEIPT_CAPACITY; i++) {
            environment.reserve(uuid(), uuid(), environment.snapshot());
        }
        Detail approved = approved();
        assertThatThrownBy(() -> service.execute(approved.id(), command(approved))).hasMessageContaining("REMEDIATION_RECEIPT_CAPACITY");
        assertThat(environment.applications).isZero();
        assertThatThrownBy(() -> new RemediationJson().write("x".repeat(70_000))).hasMessageContaining("REMEDIATION_EVIDENCE_TOO_LARGE");
    }

    /** R17：真实 HTTP 超限正文只保留有界的不明证据，核查不重发验证。 */
    @Test
    void oversizedHttpEvidenceStaysUnknownWithoutRetry() {
        environment.oversizedBody = "x".repeat(70_000);
        Detail approved = approved();
        Detail result = service.execute(approved.id(), command(approved));
        assertThat(result.execution().status()).isEqualTo("UNKNOWN");
        assertThat(result.execution().applicationStatus()).isEqualTo("APPLIED");
        assertThat(result.execution().errorCode()).isEqualTo("REMEDIATION_EVIDENCE_TOO_LARGE");
        Detail reconciled = service.reconcile(result.execution().id(), command(result));
        assertThat(reconciled.execution().status()).isEqualTo("UNKNOWN");
        assertThat(reconciled.execution().applicationStatus()).isEqualTo("APPLIED");
        assertThat(environment.applications).isEqualTo(1);
        assertThat(environment.verifications).isEqualTo(1);
        assertThat(database.jdbc.queryForObject("SELECT OCTET_LENGTH(receipt_json) FROM remediation_executions", Integer.class)).isLessThan(RemediationContract.MAX_EVIDENCE_BYTES);
    }

    /** CR-F02：200响应头之后正文停顿仍有界退出，迟到成功不覆盖UNKNOWN，准入锁释放。 */
    @Test
    void responseBodyStallTimesOutAndLateSuccessCannotUpgradeUnknown() throws Exception {
        DiagnosticEngine engine = mock(DiagnosticEngine.class);
        MemoryService memories = mock(MemoryService.class);
        when(engine.configured()).thenReturn(true);
        when(memories.list()).thenReturn(List.of());
        RunServiceImpl admission = new RunServiceImpl(database.runs, database.events, database.transactions,
                new ObjectMapper(), engine, memories, new RunStreamSignals(), 30);
        runs = admission;
        service = service(database.mapper(RemediationEventMapper.class), database.mapper(RemediationExecutionMapper.class));
        HttpServer downstream = Objects.requireNonNull((HttpServer) ReflectionTestUtils.getField(environment, "server"));
        downstream.removeContext("/downstream/v2/orders");
        CountDownLatch headers = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        AtomicInteger posts = new AtomicInteger();
        downstream.createContext("/downstream/v2/orders", exchange -> {
            posts.incrementAndGet();
            byte[] bytes = new ObjectMapper().writeValueAsBytes(Map.of("accepted", true, "path", "/v2/orders",
                    "requestId", exchange.getRequestHeaders().getFirst("X-OrderBridge-Request-Id")));
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes, 0, 1);
                output.flush();
                headers.countDown();
                if (finish.await(8, TimeUnit.SECONDS)) {
                    output.write(bytes, 1, bytes.length - 1);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (IOException exception) {
                // 客户端超时取消后关闭流是预期事实，不能给原执行补发请求。
            }
        });
        Detail approved = approved();
        long started = System.nanoTime();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Detail> execution = executor.submit(() -> {
                identity(admin);
                try {
                    return service.execute(approved.id(), command(approved));
                } finally {
                    RequestContextHolder.resetRequestAttributes();
                }
            });
            assertThat(headers.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(executor.submit(environment::snapshot).get(500, TimeUnit.MILLISECONDS).revision()).isGreaterThan(0);
            assertThatThrownBy(() -> admission.whenIdle(() -> true)).hasMessageContaining("ENVIRONMENT_OPERATION_IN_PROGRESS");
            Detail result = execution.get(7, TimeUnit.SECONDS);
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(6500);
            assertThat(result.execution().status()).isEqualTo("UNKNOWN");
            assertThat(result.execution().applicationStatus()).isEqualTo("APPLIED");
            assertThat(result.execution().verificationStatus()).isEqualTo("UNKNOWN");
            assertThat(result.execution().errorCode()).isEqualTo("VERIFICATION_BUDGET_EXCEEDED");
            assertThat(admission.whenIdle(() -> true)).isTrue();
            finish.countDown();
            assertThat(service.reconcile(result.execution().id(), command(result)).execution().status()).isEqualTo("UNKNOWN");
            assertThat(posts.get()).isEqualTo(1);
            assertThat(database.jdbc.queryForObject("SELECT verification_status FROM remediation_executions", String.class)).isEqualTo("UNKNOWN");
        } finally {
            finish.countDown();
            ReflectionTestUtils.invokeMethod(admission, "close");
        }
    }

    /** R03/R06：固定变换保留无关配置，读操作与普通同步不增加修订。 */
    @Test
    void unrelatedConfigurationAndReadRevisionArePreserved() {
        ReflectionTestUtils.setField(environment, "configuration", Map.of("orders.path", "/v2/orders", "tenant.label", "synthetic"));
        long revision = environment.snapshot().revision();
        environment.health();
        environment.synchronizeOrder();
        assertThat(environment.snapshot().revision()).isEqualTo(revision);
        Detail approved = approved();
        Detail result = service.execute(approved.id(), command(approved));
        assertThat(result.execution().status()).isEqualTo("SUCCEEDED");
        assertThat(environment.config().provided()).containsEntry("tenant.label", "synthetic").doesNotContainKey("orders.path");
    }

    /** R15：真实子JVM在三个边界强制退出，启动恢复不自动重新应用或发POST。 */
    @Test
    void realJvmCrashAtThreeStagesNeverReexecutes() throws Exception {
        for (String stage : List.of("ACCEPTED", "APPLIED", "VERIFIED")) {
            Path output = Files.createTempFile("remediation-crash-" + stage, ".log");
            String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
            String java = Path.of(System.getProperty("java.home"), "bin", executable).toString();
            Process child = new ProcessBuilder(java, "-Dsupportops.test.mysql=" + database.resources.mysql,
                    "-cp", System.getProperty("java.class.path"),
                    RemediationCrashProcess.class.getName(), database.resources.url(), admin.id(), stage)
                    .redirectErrorStream(true).redirectOutput(output.toFile()).start();
            try {
                assertThat(child.waitFor(30, TimeUnit.SECONDS)).as(stage + " " + output).isTrue();
                assertThat(child.exitValue()).as(Files.readString(output)).isEqualTo(23);
            } finally {
                // 超时或断言失败也只清理本例创建的子 JVM，保留日志供定位退出边界。
                if (child.isAlive()) {
                    child.destroyForcibly();
                    child.waitFor(5, TimeUnit.SECONDS);
                }
            }
            service.recover();
            Detail unknown = service.list(null, 0, 1).items().getFirst();
            assertThat(unknown.execution().status()).isEqualTo("UNKNOWN");
            assertThat(unknown.execution().applicationStatus()).isEqualTo("UNKNOWN");
            assertThat(environment.applications).isZero();
            assertThat(environment.verifications).isZero();
            Detail checked = service.reconcile(unknown.execution().id(), command(unknown));
            assertThat(checked.execution().status()).isEqualTo("UNKNOWN");
            service.closeUnknown(checked.execution().id(), new Command(uuid(), checked.version(), checked.planHash(), "退出后缺少原回执"));
            assertThat(database.jdbc.queryForObject("SELECT COUNT(*) FROM remediation_executions WHERE status='RUNNING'", Integer.class)).isZero();
        }
    }

    /** R10/R16：真实诊断准入与修复共享互斥；在途期间不允许人工核查收尾。 */
    @Test
    void actualAdmissionRejectsDiagnosisScenarioAndUnknownClosureWhileRepairIsInFlight() throws Exception {
        DiagnosticEngine engine = mock(DiagnosticEngine.class);
        MemoryService memories = mock(MemoryService.class);
        when(engine.configured()).thenReturn(true);
        when(memories.list()).thenReturn(List.of());
        RunServiceImpl admission = new RunServiceImpl(database.runs, database.events, database.transactions,
                new ObjectMapper(), engine, memories, new RunStreamSignals(), 30);
        runs = admission;
        service = service(database.mapper(RemediationEventMapper.class), database.mapper(RemediationExecutionMapper.class));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        environment.beforeApply = () -> {
            entered.countDown();
            try {
                if (!proceed.await(8, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("TEST_GATE_TIMEOUT");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
        };
        Detail approved = approved();
        String originalExternal = uuid();
        database.jdbc.update("INSERT INTO runs(id,session_id,question,status,answer,error_code,created_at,user_id,input_tokens,output_tokens) VALUES (?,?,'original external','COMPLETED','saved answer','','2026-09-26T00:00:00Z',?,0,0)", originalExternal, originalExternal, admin.id());
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Detail> execution = executor.submit(() -> {
                identity(admin);
                try {
                    return service.execute(approved.id(), command(approved));
                } finally {
                    RequestContextHolder.resetRequestAttributes();
                }
            });
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            Detail pending = service.get(approved.id());
            assertThat(pending.canCloseUnknown()).isFalse();
            assertThatThrownBy(() -> admission.start("new diagnosis", null, true)).hasMessageContaining("ENVIRONMENT_OPERATION_IN_PROGRESS");
            assertThat(admission.startExternal(originalExternal, "original external", admin).answer()).isEqualTo("saved answer");
            assertThatThrownBy(() -> admission.startExternal(originalExternal, "changed external", admin)).hasMessageContaining("EXTERNAL_REQUEST_CONFLICT");
            assertThatThrownBy(() -> admission.whenIdle(() -> environment.configure("healthy"))).hasMessageContaining("ENVIRONMENT_OPERATION_IN_PROGRESS");
            assertThatThrownBy(() -> service.closeUnknown(pending.execution().id(), new Command(uuid(), pending.version(), pending.planHash(), "不能抢占"))).hasMessageContaining("REMEDIATION_HANDLER_STILL_ACTIVE");
            assertThatThrownBy(() -> service.reconcile(pending.execution().id(), command(pending))).hasMessageContaining("REMEDIATION_HANDLER_STILL_ACTIVE");
            proceed.countDown();
            assertThat(execution.get(10, TimeUnit.SECONDS).execution().status()).isEqualTo("SUCCEEDED");
        } finally {
            proceed.countDown();
            ReflectionTestUtils.invokeMethod(admission, "close");
            database.jdbc.update("DELETE FROM runs WHERE id=?", originalExternal);
        }
    }

    /** R10：活跃诊断即使尚未产出结果，也不能消费审批或写现场。 */
    @Test
    void activeDiagnosisBlocksRepairBeforeAcceptance() throws Exception {
        DiagnosticEngine engine = mock(DiagnosticEngine.class);
        MemoryService memories = mock(MemoryService.class);
        when(engine.configured()).thenReturn(true);
        when(memories.list()).thenReturn(List.of());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        when(engine.execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.<BiConsumer<String, Object>>any())).thenAnswer(call -> {
            entered.countDown();
            finish.await(8, TimeUnit.SECONDS);
            return new DiagnosticEngine.Result("local lifecycle fixture", 0, 0, false);
        });
        RunServiceImpl admission = new RunServiceImpl(database.runs, database.events, database.transactions,
                new ObjectMapper(), engine, memories, new RunStreamSignals(), 30);
        runs = admission;
        service = service(database.mapper(RemediationEventMapper.class), database.mapper(RemediationExecutionMapper.class));
        Detail approved = approved();
        try {
            DiagnosisRun diagnosis = admission.start("local lifecycle fixture", null, true);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> service.execute(approved.id(), command(approved))).hasMessageContaining("DIAGNOSIS_IN_PROGRESS");
            assertThat(environment.applications).isZero();
            assertThat(database.jdbc.queryForObject("SELECT COUNT(*) FROM remediation_executions", Integer.class)).isZero();
            finish.countDown();
            admission.cancel(diagnosis.id());
        } finally {
            finish.countDown();
            ReflectionTestUtils.invokeMethod(admission, "close");
            database.jdbc.update("DELETE FROM run_events");
            database.jdbc.update("DELETE FROM runs");
        }
    }

    /** 仅测试专用真实HTTP故障控制，不给生产接口增加地址或故障参数。 */
    private static final class TestEnvironment extends DemoEnvironment {
        private int applications;
        private int verifications;
        private volatile int responseStatus = 200;
        private volatile boolean accepted = true;
        private String oversizedBody;
        private boolean dropReceipt;
        private Runnable beforeApply;

        /** 为固定v2路径安装真实HTTP响应器，保留故障初始410请求。 */
        private TestEnvironment() throws Exception {
            super(new ObjectMapper());
            HttpServer downstream = (HttpServer) ReflectionTestUtils.getField(this, "server");
            Objects.requireNonNull(downstream).createContext("/downstream/v2/orders", exchange -> {
                String requestId = exchange.getRequestHeaders().getFirst("X-OrderBridge-Request-Id");
                String response = oversizedBody == null
                        ? new ObjectMapper().writeValueAsString(Map.of("accepted", accepted, "path", "/v2/orders", "requestId", requestId == null ? "manual" : requestId))
                        : oversizedBody;
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(responseStatus, bytes.length);
                try (java.io.OutputStream output = exchange.getResponseBody()) {
                    output.write(bytes);
                }
            });
        }

        /** 记录真实适配器应用次数。 */
        @Override
        public synchronized void apply(String id) {
            if (beforeApply != null) {
                beforeApply.run();
            }
            applications++;
            super.apply(id);
        }

        /** 记录真实业务验证调用次数。 */
        @Override
        public void verify(String id) {
            verifications++;
            super.verify(id);
        }

        /** 模拟回执丢失只能保持UNKNOWN，不能返回伪造成功。 */
        @Override
        public synchronized Receipt receipt(String id) {
            return dropReceipt ? null : super.receipt(id);
        }
    }
}
