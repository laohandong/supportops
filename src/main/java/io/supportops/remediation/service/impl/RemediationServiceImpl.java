package io.supportops.remediation.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.supportops.agent.service.RunService;
import io.supportops.agent.vo.DiagnosisRun;
import io.supportops.remediation.constant.RemediationContract;
import io.supportops.remediation.dto.RemediationRequests;
import io.supportops.remediation.dto.RemediationRequests.Command;
import io.supportops.remediation.dto.RemediationRequests.Create;
import io.supportops.remediation.entity.RemediationDecisionEntity;
import io.supportops.remediation.entity.RemediationEventEntity;
import io.supportops.remediation.entity.RemediationExecutionEntity;
import io.supportops.remediation.entity.RemediationPlanEntity;
import io.supportops.remediation.entity.RemediationRequestEntity;
import io.supportops.remediation.mapper.RemediationDecisionMapper;
import io.supportops.remediation.mapper.RemediationEventMapper;
import io.supportops.remediation.mapper.RemediationExecutionMapper;
import io.supportops.remediation.mapper.RemediationPlanMapper;
import io.supportops.remediation.mapper.RemediationRequestMapper;
import io.supportops.remediation.service.OrderBridgeRemediationAdapter;
import io.supportops.remediation.service.RemediationService;
import io.supportops.remediation.service.support.RemediationJson;
import io.supportops.remediation.service.support.RemediationProcessIdentity;
import io.supportops.remediation.vo.RemediationViews.Action;
import io.supportops.remediation.vo.RemediationViews.ApplicationReceipt;
import io.supportops.remediation.vo.RemediationViews.Decision;
import io.supportops.remediation.vo.RemediationViews.Detail;
import io.supportops.remediation.vo.RemediationViews.Event;
import io.supportops.remediation.vo.RemediationViews.Execution;
import io.supportops.remediation.vo.RemediationViews.FrozenPlan;
import io.supportops.remediation.vo.RemediationViews.Page;
import io.supportops.remediation.vo.RemediationViews.Preview;
import io.supportops.remediation.vo.RemediationViews.Receipt;
import io.supportops.remediation.vo.RemediationViews.Report;
import io.supportops.remediation.vo.RemediationViews.RequestResult;
import io.supportops.remediation.vo.RemediationViews.Snapshot;
import io.supportops.remediation.vo.RemediationViews.Verification;
import io.supportops.user.CurrentUser;
import io.supportops.user.service.UserService;
import io.supportops.user.vo.UserView;
import jakarta.annotation.PostConstruct;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** 固定动作编排；短事务与 HTTP 分离，原请求先查询，恢复不重新写入。 */
@Service
@org.springframework.context.annotation.DependsOn("deploymentRuntimeGuard")
public final class RemediationServiceImpl implements RemediationService {
    /** 只按当前诊断投影持久化提案及回执，普通用户不得调用。 */
    @Override
    public List<Detail> outcomeEvidence(String sourceRunId, UserView actor, int page, int size) {
        if (actor == null || !actor.isAdmin()) {
            throw new ResponseStatusException(actor == null ? HttpStatus.UNAUTHORIZED : HttpStatus.FORBIDDEN, "ADMIN_REQUIRED");
        }
        runs.sourceForOutcome(sourceRunId, actor, false);
        if (page < 0 || size < 1 || size > 100 || page > 100000) {
            throw new IllegalArgumentException("INVALID_PAGINATION");
        }
        return plans.outcomePage(sourceRunId, (long) page * size, size).stream().map(this::detail).toList();
    }

    private static final Set<String> PLAN_STATES = Set.of("PENDING_APPROVAL", "APPROVED", "EXECUTING",
            "FINISHED", "REJECTED", "CANCELLED", "EXPIRED", "STALE");
    private static final Set<String> UNRESOLVED = Set.of("RUNNING", "UNKNOWN");
    private final RemediationPlanMapper plans;
    private final RemediationDecisionMapper decisions;
    private final RemediationExecutionMapper executions;
    private final RemediationRequestMapper requests;
    private final RemediationEventMapper audit;
    private final TransactionTemplate transactions;
    private final OrderBridgeRemediationAdapter adapter;
    private final RunService runs;
    private final UserService users;
    private final RemediationJson json;
    private final RemediationProcessIdentity process;
    private final Object commands = new Object();

    /** Demo 和诊断服务不反向依赖修复服务，保持单向业务边界。 */
    public RemediationServiceImpl(RemediationPlanMapper plans, RemediationDecisionMapper decisions,
            RemediationExecutionMapper executions, RemediationRequestMapper requests,
            RemediationEventMapper audit, TransactionTemplate transactions,
            OrderBridgeRemediationAdapter adapter, RunService runs, UserService users,
            RemediationJson json, RemediationProcessIdentity process) {
        this.plans = plans;
        this.decisions = decisions;
        this.executions = executions;
        this.requests = requests;
        this.audit = audit;
        this.transactions = transactions;
        this.adapter = adapter;
        this.runs = runs;
        this.users = users;
        this.json = json;
        this.process = process;
    }

    /** 启动仅标记确认退出的旧处理器，不续写配置或业务 POST。 */
    @PostConstruct
    public void recover() {
        String instance = adapter.snapshot().instanceId();
        transactions.executeWithoutResult(transaction -> {
            for (RemediationPlanEntity plan : plans.selectList(Wrappers.<RemediationPlanEntity>lambdaQuery()
                    .in(RemediationPlanEntity::getStatus, "PENDING_APPROVAL", "APPROVED"))) {
                if (!instance.equals(plan.getInstanceId())) {
                    transition(plan, "STALE");
                    event(plan.getId(), null, null, "INSTANCE_CHANGED", plan.getPlanHash());
                }
            }
            for (RemediationExecutionEntity execution : executions.selectList(
                    Wrappers.<RemediationExecutionEntity>lambdaQuery().eq(RemediationExecutionEntity::getStatus, "RUNNING"))) {
                if (!instance.equals(execution.getInstanceId()) && process.exited(execution)) {
                    complete(execution, null, null, true);
                    event(execution.getPlanId(), execution.getId(), null, "PROCESS_EXITED_UNKNOWN", "ORIGINAL_RECEIPT_LOST");
                }
            }
        });
    }

    /** 固定目录无模型依赖。 */
    @Override
    public List<Action> actions() {
        actor();
        return List.of(action());
    }

    /** 预检查不发订单请求或修改现场。 */
    @Override
    public Preview preview(String actionId) {
        actor();
        requireAction(actionId);
        Snapshot before = adapter.snapshot();
        return new Preview(action(), before, adapter.transformed(before), impact());
    }

    /** 创建时重新采集事实并冻结，复制也不会继承审批。 */
    @Override
    public Detail create(Create request) {
        String actor = actor();
        return command(actor, request.requestId(), "CREATE", RemediationContract.TARGET, request, () -> {
            requireAction(request.actionId());
            requireText(request.name(), 120);
            requireText(request.reason(), 1000);
            Snapshot before = adapter.snapshot();
            if (!before.applicable()) {
                throw conflict("REMEDIATION_ACTION_NOT_APPLICABLE");
            }
            requireNoUnresolved();
            List<Long> evidence = List.of();
            if (request.sourceRunId() != null) {
                runs.requireDemoRemediationSource(request.sourceRunId());
                DiagnosisRun source = runs.get(request.sourceRunId());
                if (Set.of("QUEUED", "RUNNING").contains(source.status())) {
                    throw conflict("REMEDIATION_SOURCE_NOT_FINISHED");
                }
                evidence = runs.events(source.id(), 0).stream().map(value -> value.id()).toList();
            }
            if (request.supersedesExecutionId() != null
                    && !"CLOSED_UNRESOLVED".equals(execution(request.supersedesExecutionId()).getStatus())) {
                throw conflict("REMEDIATION_SUPERSEDES_INVALID");
            }
            String id = uuid();
            String created = now();
            long expires = System.currentTimeMillis() + RemediationContract.PLAN_TTL_MS;
            FrozenPlan frozen = new FrozenPlan(RemediationContract.SCHEMA_VERSION, id, action(), before,
                    adapter.transformed(before), request.name(), request.reason(), actor, request.sourceRunId(),
                    evidence, request.supersedesExecutionId(), created, expires,
                    RemediationContract.APPROVAL_POLICY_VERSION, impact());
            String encoded = json.write(frozen);
            RemediationPlanEntity plan = new RemediationPlanEntity();
            plan.setId(id);
            plan.setName(request.name());
            plan.setStatus("PENDING_APPROVAL");
            plan.setVersion(1);
            plan.setPlanHash(RemediationJson.hash(encoded));
            plan.setSnapshotJson(encoded);
            plan.setCreatorId(actor);
            plan.setSourceRunId(request.sourceRunId());
            plan.setTargetId(before.targetId());
            plan.setInstanceId(before.instanceId());
            plan.setCreatedAt(created);
            plan.setExpiresAt(expires);
            single(plans.insert(plan));
            event(id, null, actor, "CREATED", plan.getPlanHash());
            return new Reference(id, null);
        });
    }

    /** 稳定时间/ID 排序，分页参数经服务端验证后进入固定 LIMIT。 */
    @Override
    public Page list(String status, int page, int size) {
        actor();
        if (page < 0 || page > 100_000 || size < 1 || size > 100
                || (status != null && !PLAN_STATES.contains(status))) {
            throw invalid();
        }
        long total = plans.selectCount(Wrappers.<RemediationPlanEntity>lambdaQuery()
                .eq(status != null, RemediationPlanEntity::getStatus, status));
        List<Detail> items = plans.selectList(Wrappers.<RemediationPlanEntity>lambdaQuery()
                .eq(status != null, RemediationPlanEntity::getStatus, status)
                .orderByDesc(RemediationPlanEntity::getCreatedAt, RemediationPlanEntity::getId)
                .last("LIMIT " + size + " OFFSET " + (long) page * size)).stream().map(this::detail).toList();
        return new Page(items, page, size, total);
    }

    /** GET 不执行状态迁移或补发请求。 */
    @Override
    public Detail get(String id) {
        actor();
        return detail(plan(id));
    }

    /** 批准只保存一次决策。 */
    @Override
    public Detail approve(String id, Command request) {
        return decide(id, request, "APPROVE");
    }

    /** 驳回理由不能为空。 */
    @Override
    public Detail reject(String id, Command request) {
        return decide(id, request, "REJECT");
    }

    /** 一提案一决定，由行锁和唯一约束共同保护。 */
    private Detail decide(String id, Command request, String kind) {
        String actor = actor();
        return command(actor, request.requestId(), kind, id, request, () -> {
            RemediationPlanEntity plan = locked(id, request);
            requireState(plan, "PENDING_APPROVAL");
            if ("APPROVE".equals(kind)) {
                requireFresh(plan);
            } else {
                requireText(request.reason(), 1000);
            }
            RemediationDecisionEntity decision = new RemediationDecisionEntity();
            decision.setId(uuid());
            decision.setPlanId(id);
            decision.setPlanHash(plan.getPlanHash());
            decision.setKind(kind);
            decision.setActorId(actor);
            decision.setReason(request.reason() == null ? "" : request.reason());
            decision.setExpiresAt(Math.min(plan.getExpiresAt(), System.currentTimeMillis() + RemediationContract.APPROVAL_TTL_MS));
            decision.setCreatedAt(now());
            single(decisions.insert(decision));
            transition(plan, "APPROVE".equals(kind) ? "APPROVED" : "REJECTED");
            event(id, null, actor, kind, decisionView(decision));
            return new Reference(id, null);
        });
    }

    /** 撤销与执行消费不能同时成功。 */
    @Override
    public Detail cancel(String id, Command request) {
        String actor = actor();
        return command(actor, request.requestId(), "CANCEL", id, request, () -> {
            RemediationPlanEntity plan = locked(id, request);
            if (!Set.of("PENDING_APPROVAL", "APPROVED").contains(plan.getStatus())) {
                throw conflict("REMEDIATION_STATE_CONFLICT");
            }
            transition(plan, "CANCELLED");
            event(id, null, actor, "CANCELLED", plan.getPlanHash());
            return new Reference(id, null);
        });
    }

    /** 原请求先查询；准入边界覆盖受理、应用、验证和收尾，网络不在数据库事务内。 */
    @Override
    public Detail execute(String id, Command request) {
        String actor = actor();
        Detail existing = replay(actor, request.requestId(), "EXECUTE", id, request);
        if (existing != null) {
            return existing;
        }
        return runs.whenIdle(() -> executeAdmitted(id, request, actor));
    }

    /** 短事务 A 消费审批并保存原操作身份，提交成功后才开始外部副作用。 */
    private Detail executeAdmitted(String id, Command request, String actor) {
        String operationId = uuid();
        String executionId = uuid();
        String verificationId = uuid();
        Detail accepted;
        try {
            accepted = command(actor, request.requestId(), "EXECUTE", id, request, () -> {
                RemediationPlanEntity plan = locked(id, request);
                requireState(plan, "APPROVED");
                requireFresh(plan);
                requireNoUnresolved();
                RemediationDecisionEntity decision = decision(id);
                if (decision == null || !"APPROVE".equals(decision.getKind())
                        || !plan.getPlanHash().equals(decision.getPlanHash())) {
                    throw conflict("REMEDIATION_APPROVAL_REQUIRED");
                }
                requireAdmin(decision.getActorId());
                requireAdmin(actor);
                if (decision.getExpiresAt() <= System.currentTimeMillis()) {
                    throw conflict("REMEDIATION_APPROVAL_EXPIRED");
                }
                FrozenPlan frozen = frozen(plan);
                adapter.reserve(operationId, verificationId, frozen.before());
                RemediationExecutionEntity execution = new RemediationExecutionEntity();
                execution.setId(executionId);
                execution.setPlanId(id);
                execution.setTargetId(plan.getTargetId());
                execution.setOperationId(operationId);
                execution.setVerificationRequestId(verificationId);
                execution.setInstanceId(frozen.before().instanceId());
                execution.setProcessId(process.pid());
                execution.setProcessStartedAt(process.startedAt());
                execution.setActorId(actor);
                execution.setStatus("RUNNING");
                execution.setApplicationStatus("NOT_STARTED");
                execution.setVerificationStatus("NOT_RUN");
                execution.setErrorCode("");
                execution.setCreatedAt(now());
                single(executions.insert(execution));
                transition(plan, "EXECUTING");
                event(id, executionId, actor, "ACCEPTED", plan.getPlanHash());
                return new Reference(id, executionId);
            });
        } catch (RuntimeException exception) {
            adapter.exited(operationId);
            adapter.release(operationId);
            throw exception;
        }
        if (accepted.execution() == null || !executionId.equals(accepted.execution().id())) {
            return accepted;
        }
        boolean persisted = false;
        try {
            try {
                adapter.apply(operationId);
                adapter.verify(operationId);
            } catch (RuntimeException exception) {
                // 异常只进入原回执核查，绝不补偿或再发业务 POST。
            }
            Receipt observed = adapter.receipt(operationId);
            Receipt evidence = observed == null ? null : new Receipt(observed.application(), observed.verification(), false);
            try {
                transactions.executeWithoutResult(transaction -> complete(execution(executionId), evidence, actor, false));
            } catch (RuntimeException exception) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "REMEDIATION_RESULT_NOT_PERSISTED");
            }
            Detail result = get(id);
            persisted = !UNRESOLVED.contains(result.execution().status());
            return result;
        } finally {
            // 原处理器登记覆盖完成事务；核查不能趁应用结束至落库之间抢占。
            adapter.exited(operationId);
            if (persisted) {
                adapter.release(operationId);
            }
        }
    }

    /** 核查只读取原回执，缺证据仍不明。 */
    @Override
    public Detail reconcile(String executionId, Command request) {
        String actor = actor();
        Detail result = command(actor, request.requestId(), "RECONCILE", executionId, request, () -> {
            RemediationExecutionEntity execution = execution(executionId);
            RemediationPlanEntity plan = locked(execution.getPlanId(), request);
            requireUnresolved(execution);
            requireExited(execution);
            Receipt receipt = execution.getInstanceId().equals(adapter.snapshot().instanceId())
                    ? adapter.receipt(execution.getOperationId()) : null;
            complete(execution, receipt, actor, true);
            event(plan.getId(), executionId, actor, "RECONCILED", receipt == null ? "ORIGINAL_RECEIPT_UNAVAILABLE" : receipt);
            return new Reference(plan.getId(), executionId);
        });
        if (!UNRESOLVED.contains(result.execution().status())) {
            adapter.release(result.execution().operationId());
        }
        return result;
    }

    /** 人工结束保留未知事实，不能借此改成成功或失败。 */
    @Override
    public Detail closeUnknown(String executionId, Command request) {
        String actor = actor();
        Detail result = command(actor, request.requestId(), "CLOSE_UNKNOWN", executionId, request, () -> {
            requireText(request.reason(), 1000);
            RemediationExecutionEntity execution = execution(executionId);
            RemediationPlanEntity plan = locked(execution.getPlanId(), request);
            requireUnresolved(execution);
            requireExited(execution);
            single(executions.update(Wrappers.<RemediationExecutionEntity>lambdaUpdate()
                    .eq(RemediationExecutionEntity::getId, executionId)
                    .eq(RemediationExecutionEntity::getStatus, execution.getStatus())
                    .set(RemediationExecutionEntity::getStatus, "CLOSED_UNRESOLVED")
                    .set(RemediationExecutionEntity::getApplicationStatus,
                            "NOT_STARTED".equals(execution.getApplicationStatus()) ? "UNKNOWN" : execution.getApplicationStatus())
                    .set(RemediationExecutionEntity::getVerificationStatus,
                            "NOT_RUN".equals(execution.getVerificationStatus()) ? "UNKNOWN" : execution.getVerificationStatus())
                    .set(RemediationExecutionEntity::getFinishedAt, now())));
            if ("EXECUTING".equals(plan.getStatus())) {
                transition(plan, "FINISHED");
            }
            event(plan.getId(), executionId, actor, "CLOSED_UNRESOLVED", request.reason());
            return new Reference(plan.getId(), executionId);
        });
        adapter.release(result.execution().operationId());
        return result;
    }

    /** 解析或字段校验失败仍登记原请求；冲突不能作为新的未执行证明。 */
    @Override
    public void rejectInvalidInput(String requestId, String kind, String target, String digest) {
        String actor = actor();
        if (requestId == null || !requestId.matches(RemediationRequests.UUID_PATTERN)
                || !Set.of("CREATE", "APPROVE", "REJECT", "CANCEL", "EXECUTE", "RECONCILE", "CLOSE_UNKNOWN").contains(kind)
                || target == null || target.length() > 64 || digest == null || !digest.matches("[a-f0-9]{64}")) {
            throw invalid();
        }
        synchronized (commands) {
            RemediationRequestEntity existing = findRequest(actor, requestId);
            if (existing != null) {
                if (!kind.equals(existing.getKind()) || !target.equals(existing.getTargetId())
                        || !digest.equals(existing.getDigest())) {
                    throw conflict("REMEDIATION_REQUEST_CONFLICT");
                }
                // 旧结果始终优先，不能用本次字段拒绝改写已经发生的操作。
                if (existing.getHttpStatus() >= 400) {
                    throw new ResponseStatusException(HttpStatus.valueOf(existing.getHttpStatus()), existing.getErrorCode());
                }
                throw invalid();
            }
            transactions.executeWithoutResult(transaction -> saveRequestDigest(actor, requestId, kind, target,
                    digest, null, 400, "INVALID_INPUT"));
        }
        throw invalid();
    }

    /** 查询仅当前账号固定请求，拒绝结果也稳定保存。 */
    @Override
    public RequestResult request(String requestId) {
        RemediationRequestEntity request = findRequest(actor(), requestId);
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "REMEDIATION_REQUEST_NOT_FOUND");
        }
        return new RequestResult(requestId, request.getKind(), request.getTargetId(), request.getHttpStatus(),
                request.getErrorCode(), request.getPlanId(), request.getExecutionId(),
                request.getPlanId() == null ? null : detail(plan(request.getPlanId())));
    }

    /** 按递增游标读取追加事件，单页最多200条。 */
    @Override
    public List<Event> events(String id, long after) {
        actor();
        plan(id);
        if (after < 0) {
            throw invalid();
        }
        return audit.selectList(Wrappers.<RemediationEventEntity>lambdaQuery()
                .eq(RemediationEventEntity::getPlanId, id).gt(RemediationEventEntity::getId, after)
                .orderByAsc(RemediationEventEntity::getId).last("LIMIT 200")).stream().map(this::eventView).toList();
    }

    /** 报告只读取已保存记录，证据分类固定为合成回环环境。 */
    @Override
    public Report report(String id) {
        Detail detail = get(id);
        List<Event> events = audit.selectList(Wrappers.<RemediationEventEntity>lambdaQuery()
                .eq(RemediationEventEntity::getPlanId, id).orderByAsc(RemediationEventEntity::getId)
                .last("LIMIT 1001")).stream().map(this::eventView).toList();
        if (events.size() > 1000) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "REMEDIATION_REPORT_TOO_LARGE");
        }
        return new Report("SYNTHETIC_LOOPBACK_HTTP", detail, events);
    }

    /** 同进程串行短事务，数据库唯一约束保护幂等；失败正文只保存摘要。 */
    private Detail command(String actor, String requestId, String kind, String target, Object body,
            Supplier<Reference> operation) {
        if (requestId == null || !requestId.matches(RemediationRequests.UUID_PATTERN)) {
            throw invalid();
        }
        synchronized (commands) {
            Detail previous = replay(actor, requestId, kind, target, body);
            if (previous != null) {
                return previous;
            }
            try {
                Reference reference = transactions.execute(transaction -> {
                    Reference result = operation.get();
                    saveRequest(actor, requestId, kind, target, body, result, 200, "");
                    return result;
                });
                return detail(plan(Objects.requireNonNull(reference).planId()));
            } catch (ResponseStatusException exception) {
                transactions.executeWithoutResult(transaction -> {
                    saveRequest(actor, requestId, kind, target, body, null,
                            exception.getStatusCode().value(), exception.getReason());
                    persistInvalidation(target, exception.getReason(), actor);
                });
                throw exception;
            }
        }
    }

    /** 同编号不同目标/正文冲突；已持久化拒绝不随环境改变而复活。 */
    private Detail replay(String actor, String requestId, String kind, String target, Object body) {
        RemediationRequestEntity existing = findRequest(actor, requestId);
        if (existing == null) {
            return null;
        }
        if (!kind.equals(existing.getKind()) || !target.equals(existing.getTargetId())
                || !RemediationJson.hash(json.write(body)).equals(existing.getDigest())) {
            throw conflict("REMEDIATION_REQUEST_CONFLICT");
        }
        if (existing.getHttpStatus() >= 400) {
            throw new ResponseStatusException(HttpStatus.valueOf(existing.getHttpStatus()), existing.getErrorCode());
        }
        return detail(plan(existing.getPlanId()));
    }

    /** 请求受理与审批/执行身份处于同一事务。 */
    private void saveRequest(String actor, String requestId, String kind, String target, Object body,
            Reference reference, int status, String error) {
        saveRequestDigest(actor, requestId, kind, target, RemediationJson.hash(json.write(body)), reference, status, error);
    }

    /** 有效命令和输入拒绝使用同一账号、编号、目标和正文摘要唯一约束。 */
    private void saveRequestDigest(String actor, String requestId, String kind, String target, String digest,
            Reference reference, int status, String error) {
        RemediationRequestEntity request = new RemediationRequestEntity();
        request.setId(uuid());
        request.setActorId(actor);
        request.setRequestId(requestId);
        request.setKind(kind);
        request.setTargetId(target);
        request.setDigest(digest);
        request.setPlanId(reference == null ? null : reference.planId());
        request.setExecutionId(reference == null ? null : reference.executionId());
        request.setHttpStatus(status);
        request.setErrorCode(error == null ? "" : error);
        request.setCreatedAt(now());
        single(requests.insert(request));
    }

    /** 账号内唯一请求读取。 */
    private RemediationRequestEntity findRequest(String actor, String requestId) {
        return requests.selectOne(Wrappers.<RemediationRequestEntity>lambdaQuery()
                .eq(RemediationRequestEntity::getActorId, actor).eq(RemediationRequestEntity::getRequestId, requestId));
    }

    /** 短事务 B 原子提交证据、结论与事件；迟到响应不得覆盖合法终态。 */
    private void complete(RemediationExecutionEntity execution, Receipt receipt, String actor, boolean reconcile) {
        String oldStatus = execution.getStatus();
        if (!("RUNNING".equals(oldStatus) || (reconcile && "UNKNOWN".equals(oldStatus)))) {
            throw conflict("REMEDIATION_STATE_CONFLICT");
        }
        RemediationPlanEntity plan = plans.lock(execution.getPlanId());
        FrozenPlan frozen = frozen(plan);
        if (receipt == null && execution.getReceiptJson() != null) {
            // 内存证据可能在重启后丢失，已持久化的原应用事实不能被核查降格或抹去。
            receipt = json.read(execution.getReceiptJson(), Receipt.class);
        }
        String application = "UNKNOWN";
        String verification = "UNKNOWN";
        String status = "UNKNOWN";
        String error = "ORIGINAL_RECEIPT_UNAVAILABLE";
        if (receipt != null && !receipt.inFlight() && validApplication(execution, frozen, receipt.application())) {
            application = receipt.application().status();
            if ("NOT_APPLIED".equals(application)) {
                verification = "NOT_RUN";
                status = "FAILED";
                error = receipt.application().errorCode();
            } else if ("APPLIED".equals(application)) {
                Verification evidence = receipt.verification();
                verification = validVerification(execution, receipt.application(), evidence) ? evidence.status() : "UNKNOWN";
                if ("PASSED".equals(verification)) {
                    status = "SUCCEEDED";
                    error = "";
                } else if ("FAILED".equals(verification)) {
                    status = "VERIFICATION_FAILED";
                    error = evidence.errorCode();
                } else {
                    if (evidence == null) {
                        error = "VERIFICATION_NOT_RECORDED";
                    } else if (evidence.errorCode().isEmpty()) {
                        error = "VERIFICATION_EVIDENCE_INVALID";
                    } else {
                        error = evidence.errorCode();
                    }
                }
            }
        }
        String encoded = receipt == null ? null : json.write(receipt);
        single(executions.update(Wrappers.<RemediationExecutionEntity>lambdaUpdate()
                .eq(RemediationExecutionEntity::getId, execution.getId()).eq(RemediationExecutionEntity::getStatus, oldStatus)
                .set(RemediationExecutionEntity::getStatus, status)
                .set(RemediationExecutionEntity::getApplicationStatus, application)
                .set(RemediationExecutionEntity::getVerificationStatus, verification)
                .set(RemediationExecutionEntity::getReceiptJson, encoded)
                .set(RemediationExecutionEntity::getErrorCode, error).set(RemediationExecutionEntity::getFinishedAt, now())));
        if ("EXECUTING".equals(plan.getStatus())) {
            transition(plan, "FINISHED");
        }
        event(plan.getId(), execution.getId(), actor, reconcile ? "RECONCILIATION_RESULT" : "FINISHED",
                new Completion(status, application, verification, error, receipt));
    }

    /** 回执必须匹配原操作、实例、精确配置与修订。 */
    private boolean validApplication(RemediationExecutionEntity execution, FrozenPlan plan, ApplicationReceipt receipt) {
        if (receipt == null || !execution.getOperationId().equals(receipt.operationId())
                || !execution.getInstanceId().equals(receipt.instanceId())
                || receipt.beforeRevision() != plan.before().revision()
                || !receipt.before().equals(plan.before().provided())) {
            return false;
        }
        return "NOT_APPLIED".equals(receipt.status()) || ("APPLIED".equals(receipt.status())
                && receipt.after().equals(plan.after()) && receipt.afterRevision() == receipt.beforeRevision() + 1
                && receipt.appliedAt() != null);
    }

    /** 成功须有原 HTTP 回显、配置读回和应用之后的时间顺序。 */
    private boolean validVerification(RemediationExecutionEntity execution, ApplicationReceipt application, Verification evidence) {
        if (evidence == null || !execution.getVerificationRequestId().equals(evidence.requestId())
                || !execution.getOperationId().equals(evidence.operationId())
                || !execution.getInstanceId().equals(evidence.instanceId())
                || !RemediationContract.PATH.equals(evidence.path())
                || evidence.revision() != application.afterRevision()
                || !evidence.configReadback().equals(application.after())
                || Instant.parse(evidence.startedAt()).isBefore(Instant.parse(application.appliedAt()))
                || Instant.parse(evidence.finishedAt()).isBefore(Instant.parse(evidence.startedAt()))
                || Instant.parse(evidence.finishedAt()).isAfter(Instant.parse(execution.getCreatedAt())
                        .plusSeconds(RemediationContract.EXECUTION_BUDGET_SECONDS))) {
            return false;
        }
        if (!"PASSED".equals(evidence.status())) {
            return Set.of("FAILED", "UNKNOWN").contains(evidence.status());
        }
        return Integer.valueOf(200).equals(evidence.httpStatus()) && Boolean.TRUE.equals(evidence.accepted())
                && evidence.requestId().equals(evidence.echoedRequestId()) && evidence.path().equals(evidence.echoedPath());
    }

    /** 失败事务回滚后再记录过期/失效，GET 不改变状态。 */
    private void persistInvalidation(String target, String error, String actor) {
        String next = null;
        if ("REMEDIATION_ENVIRONMENT_CHANGED".equals(error) || "REMEDIATION_ACTION_CHANGED".equals(error)) {
            next = "STALE";
        } else if ("REMEDIATION_PLAN_EXPIRED".equals(error) || "REMEDIATION_APPROVAL_EXPIRED".equals(error)) {
            next = "EXPIRED";
        }
        if (next != null) {
            RemediationPlanEntity plan = plans.lock(target);
            if (plan != null && Set.of("PENDING_APPROVAL", "APPROVED").contains(plan.getStatus())) {
                transition(plan, next);
                event(target, null, actor, next, error);
            }
        }
    }

    /** 独立展示提案、决策、应用和验证；能力不是命令授权。 */
    private Detail detail(RemediationPlanEntity plan) {
        FrozenPlan frozen = frozen(plan);
        RemediationDecisionEntity decision = decision(plan.getId());
        RemediationExecutionEntity execution = executions.selectOne(Wrappers.<RemediationExecutionEntity>lambdaQuery()
                .eq(RemediationExecutionEntity::getPlanId, plan.getId()));
        String blocked = "";
        if (Set.of("PENDING_APPROVAL", "APPROVED").contains(plan.getStatus())) {
            if (plan.getExpiresAt() <= System.currentTimeMillis()
                    || (decision != null && decision.getExpiresAt() <= System.currentTimeMillis())) {
                blocked = "REMEDIATION_PLAN_EXPIRED";
            } else if (!sameEnvironment(frozen.before(), adapter.snapshot())) {
                blocked = "REMEDIATION_ENVIRONMENT_CHANGED";
            }
        }
        boolean fresh = blocked.isEmpty();
        boolean unresolved = execution != null && UNRESOLVED.contains(execution.getStatus());
        boolean exited = unresolved && handlerExited(execution);
        return new Detail(plan.getId(), plan.getStatus(), plan.getVersion(), plan.getPlanHash(), frozen,
                decisionView(decision), executionView(execution), fresh && "PENDING_APPROVAL".equals(plan.getStatus()),
                fresh && "APPROVED".equals(plan.getStatus()), Set.of("PENDING_APPROVAL", "APPROVED").contains(plan.getStatus()),
                exited, exited, blocked);
    }

    /** 公开执行模型只含脱敏回执与操作身份。 */
    private Execution executionView(RemediationExecutionEntity value) {
        if (value == null) {
            return null;
        }
        return new Execution(value.getId(), value.getPlanId(), value.getOperationId(), value.getVerificationRequestId(),
                value.getInstanceId(), value.getActorId(), value.getStatus(), value.getApplicationStatus(),
                value.getVerificationStatus(), value.getReceiptJson() == null ? null : json.read(value.getReceiptJson(), Receipt.class),
                value.getErrorCode(), value.getCreatedAt(), value.getFinishedAt());
    }

    /** 审批身份真实记名。 */
    private Decision decisionView(RemediationDecisionEntity value) {
        return value == null ? null : new Decision(value.getId(), value.getKind(), value.getActorId(), value.getPlanHash(),
                value.getReason(), value.getExpiresAt(), value.getCreatedAt());
    }

    /** 游标审计公开映射。 */
    private Event eventView(RemediationEventEntity value) {
        return new Event(value.getId(), value.getExecutionId(), value.getActorId(), value.getKind(), value.getPayloadJson(), value.getCreatedAt());
    }

    /** 读取提案唯一决策。 */
    private RemediationDecisionEntity decision(String id) {
        return decisions.selectOne(Wrappers.<RemediationDecisionEntity>lambdaQuery().eq(RemediationDecisionEntity::getPlanId, id));
    }

    /** 读取冻结快照时复核 SHA256，损坏不得执行。 */
    private FrozenPlan frozen(RemediationPlanEntity plan) {
        if (!plan.getPlanHash().equals(RemediationJson.hash(plan.getSnapshotJson()))) {
            throw conflict("REMEDIATION_PLAN_CORRUPTED");
        }
        return json.read(plan.getSnapshotJson(), FrozenPlan.class);
    }

    /** 审批和执行都检查时效、动作版本、实际环境。 */
    private void requireFresh(RemediationPlanEntity plan) {
        if (plan.getExpiresAt() <= System.currentTimeMillis()) {
            throw conflict("REMEDIATION_PLAN_EXPIRED");
        }
        FrozenPlan frozen = frozen(plan);
        if (!action().equals(frozen.action()) || frozen.schemaVersion() != RemediationContract.SCHEMA_VERSION
                || frozen.approvalPolicyVersion() != RemediationContract.APPROVAL_POLICY_VERSION) {
            throw conflict("REMEDIATION_ACTION_CHANGED");
        }
        if (!sameEnvironment(frozen.before(), adapter.snapshot())) {
            throw conflict("REMEDIATION_ENVIRONMENT_CHANGED");
        }
    }

    /** 配置改回、同名重置、跨实例都不能恢复旧审批。 */
    private boolean sameEnvironment(Snapshot expected, Snapshot actual) {
        return actual.applicable() && expected.targetId().equals(actual.targetId())
                && expected.instanceId().equals(actual.instanceId()) && expected.revision() == actual.revision()
                && expected.fingerprint().equals(actual.fingerprint()) && expected.provided().equals(actual.provided())
                && expected.appVersion().equals(actual.appVersion()) && expected.effectivePath().equals(actual.effectivePath());
    }

    /** 短事务行锁和版本/hash 双重绑定。 */
    private RemediationPlanEntity locked(String id, Command request) {
        RemediationPlanEntity plan = plans.lock(id);
        if (plan == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "REMEDIATION_NOT_FOUND");
        }
        if (plan.getVersion() != request.expectedVersion()) {
            throw conflict("REMEDIATION_VERSION_CONFLICT");
        }
        if (!plan.getPlanHash().equals(request.planHash())) {
            throw conflict("REMEDIATION_PLAN_HASH_CONFLICT");
        }
        return plan;
    }

    /** 条件更新并检查影响行数，禁止覆盖其他事务的终态。 */
    private void transition(RemediationPlanEntity plan, String status) {
        single(plans.update(Wrappers.<RemediationPlanEntity>lambdaUpdate()
                .eq(RemediationPlanEntity::getId, plan.getId()).eq(RemediationPlanEntity::getVersion, plan.getVersion())
                .eq(RemediationPlanEntity::getStatus, plan.getStatus()).set(RemediationPlanEntity::getStatus, status)
                .set(RemediationPlanEntity::getVersion, plan.getVersion() + 1)));
        plan.setStatus(status);
        plan.setVersion(plan.getVersion() + 1);
    }

    /** 资源缺失明确404。 */
    private RemediationPlanEntity plan(String id) {
        RemediationPlanEntity plan = plans.selectById(id);
        if (plan == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "REMEDIATION_NOT_FOUND");
        }
        return plan;
    }

    /** 缺少执行行不代表未应用。 */
    private RemediationExecutionEntity execution(String id) {
        RemediationExecutionEntity execution = executions.selectById(id);
        if (execution == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "REMEDIATION_EXECUTION_NOT_FOUND");
        }
        return execution;
    }

    /** 同一目标的历史未决阻止新修复，包括旧实例。 */
    private void requireNoUnresolved() {
        if (executions.selectCount(Wrappers.<RemediationExecutionEntity>lambdaQuery()
                .eq(RemediationExecutionEntity::getTargetId, RemediationContract.TARGET)
                .in(RemediationExecutionEntity::getStatus, UNRESOLVED)) > 0) {
            throw conflict("REMEDIATION_UNRESOLVED_BLOCKS_TARGET");
        }
    }

    /** 本实例用在途登记，旧实例用 PID/启动身份确认退出。 */
    private boolean handlerExited(RemediationExecutionEntity execution) {
        if (execution.getInstanceId().equals(adapter.instanceId())) {
            return !adapter.inFlight(execution.getOperationId());
        }
        return process.exited(execution);
    }

    /** 不明进程或在途处理器不能被核查抢占。 */
    private void requireExited(RemediationExecutionEntity execution) {
        if (!handlerExited(execution)) {
            throw conflict("REMEDIATION_HANDLER_STILL_ACTIVE");
        }
    }

    /** 已知终态不能由核查或人工结束覆盖。 */
    private void requireUnresolved(RemediationExecutionEntity execution) {
        if (!UNRESOLVED.contains(execution.getStatus())) {
            throw conflict("REMEDIATION_STATE_CONFLICT");
        }
    }

    /** 状态准入不依赖网页能力提示。 */
    private void requireState(RemediationPlanEntity plan, String expected) {
        if (!expected.equals(plan.getStatus())) {
            throw conflict("REMEDIATION_STATE_CONFLICT");
        }
    }

    /** 首版只注册固定动作。 */
    private void requireAction(String id) {
        if (!RemediationContract.ACTION.equals(id)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "REMEDIATION_ACTION_NOT_SUPPORTED");
        }
    }

    /** 服务层再次验证当前账号，正文不能指定 actor。 */
    private String actor() {
        String id = CurrentUser.id();
        requireAdmin(id);
        return id;
    }

    /** 审批者与执行者的当前有效权限均重新查询。 */
    private void requireAdmin(String id) {
        UserView user = id == null ? null : users.find(id);
        if (user == null || !user.isAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ADMIN_REQUIRED");
        }
    }

    /** 审计与状态写入同事务，不覆盖旧事件。 */
    private void event(String planId, String executionId, String actor, String kind, Object payload) {
        RemediationEventEntity event = new RemediationEventEntity();
        event.setPlanId(planId);
        event.setExecutionId(executionId);
        event.setActorId(actor);
        event.setKind(kind);
        event.setPayloadJson(json.write(payload));
        event.setCreatedAt(now());
        single(audit.insert(event));
    }

    /** 固定动作与一次验证预算。 */
    private Action action() {
        return new Action(RemediationContract.ACTION, RemediationContract.ACTION_VERSION,
                RemediationContract.VERIFICATION_VERSION, "OrderBridge 2.0 路径配置迁移", 1, RemediationContract.EXECUTION_BUDGET_SECONDS);
    }

    /** 冻结可审阅的影响和失败边界。 */
    private String impact() {
        return "删除 orders.path，设置 sync.targetPath=/v2/orders，保留其他配置。执行后发送一次合成订单请求；"
                + "验证失败保留已应用配置，不自动回滚；结果不明只核查原请求。本流程不是企业订单系统验收。";
    }

    /** 人工文本有界且必填理由不能空白。 */
    private void requireText(String value, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw invalid();
        }
    }

    /** 状态条件必须唯一命中。 */
    private void single(int count) {
        if (count != 1) {
            throw conflict("REMEDIATION_STATE_CONFLICT");
        }
    }

    /** 内部操作 UUID。 */
    private String uuid() {
        return UUID.randomUUID().toString();
    }

    /** UTC 时间用于展示，关联身份与 revision 用于操作证明。 */
    private String now() {
        return Instant.now().toString();
    }

    /** 稳定冲突不返回底层异常。 */
    private ResponseStatusException conflict(String code) {
        return new ResponseStatusException(HttpStatus.CONFLICT, code);
    }

    /** 有界参数错误。 */
    private ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
    }

    /** 请求结果仅保存引用，不保存敏感正文。 */
    private record Reference(String planId, String executionId) {}

    /** 完成审计分别记录应用、验证与综合状态。 */
    private record Completion(String status, String applicationStatus, String verificationStatus, String errorCode, Receipt receipt) {}
}
