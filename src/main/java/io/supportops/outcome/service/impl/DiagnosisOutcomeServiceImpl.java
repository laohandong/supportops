package io.supportops.outcome.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.supportops.agent.service.RunService;
import io.supportops.agent.vo.DiagnosisEvent;
import io.supportops.agent.vo.DiagnosisRun;
import io.supportops.api.support.FixedCommandJson;
import io.supportops.outcome.constant.OutcomeEvidenceType;
import io.supportops.outcome.constant.OutcomeStatus;
import io.supportops.outcome.dto.OutcomeRequests;
import io.supportops.outcome.dto.OutcomeRequests.Evidence;
import io.supportops.outcome.dto.OutcomeRequests.Save;
import io.supportops.outcome.entity.DiagnosisOutcomeEntity;
import io.supportops.outcome.entity.DiagnosisOutcomeRevisionEntity;
import io.supportops.outcome.entity.DiagnosisOutcomeRequestEntity;
import io.supportops.outcome.mapper.DiagnosisOutcomeMapper;
import io.supportops.outcome.mapper.DiagnosisOutcomeRevisionMapper;
import io.supportops.outcome.mapper.DiagnosisOutcomeRequestMapper;
import io.supportops.outcome.service.DiagnosisOutcomeService;
import io.supportops.outcome.vo.OutcomeViews.AdminEvidence;
import io.supportops.outcome.vo.OutcomeViews.Content;
import io.supportops.outcome.vo.OutcomeViews.Detail;
import io.supportops.outcome.vo.OutcomeViews.EvidenceSnapshot;
import io.supportops.outcome.vo.OutcomeViews.EvidenceView;
import io.supportops.outcome.vo.OutcomeViews.History;
import io.supportops.outcome.vo.OutcomeViews.RequestResult;
import io.supportops.outcome.vo.OutcomeViews.Revision;
import io.supportops.outcome.vo.OutcomeViews.Source;
import io.supportops.outcome.vo.OutcomeViews.UserEvidence;
import io.supportops.remediation.service.RemediationService;
import io.supportops.remediation.vo.RemediationViews;
import io.supportops.remediation.vo.RemediationViews.Execution;
import io.supportops.user.CurrentUser;
import io.supportops.user.vo.UserView;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 短事务按请求、来源诊断、结果主记录加锁；业务拒绝回滚保存点但保留原回执。 */
@Service
public final class DiagnosisOutcomeServiceImpl implements DiagnosisOutcomeService {
    private static final Set<String> TERMINAL = Set.of("COMPLETED", "FAILED", "CANCELLED", "TIMED_OUT", "INTERRUPTED", "LIMIT_REACHED");
    private final DiagnosisOutcomeMapper outcomes;
    private final DiagnosisOutcomeRevisionMapper revisions;
    private final DiagnosisOutcomeRequestMapper requests;
    private final RunService runs;
    private final RemediationService remediation;
    private final TransactionTemplate transactions;
    private final FixedCommandJson json;

    /** 单向依赖来源服务；不访问其他模块Mapper。 */
    public DiagnosisOutcomeServiceImpl(DiagnosisOutcomeMapper outcomes, DiagnosisOutcomeRevisionMapper revisions,
            DiagnosisOutcomeRequestMapper requests, RunService runs, RemediationService remediation,
            TransactionTemplate transactions, FixedCommandJson json) {
        this.outcomes = outcomes;
        this.revisions = revisions;
        this.requests = requests;
        this.runs = runs;
        this.remediation = remediation;
        this.transactions = transactions;
        this.json = json;
    }

    /** 每次重新验证身份和来源，查询没有隐式写入。 */
    @Override
    public Detail get(String runId) {
        UserView actor = actor();
        DiagnosisRun run = authorized(runId, actor, false);
        DiagnosisOutcomeEntity outcome = find(runId);
        boolean terminal = TERMINAL.contains(run.status());
        return new Detail(runId, outcome == null ? null : outcome.getId(), outcome == null ? 0 : outcome.getVersion(),
                outcome == null ? null : revision(outcome.getCurrentRevisionId(), actor), terminal, actor.isAdmin() && outcome != null);
    }

    /** 使用经过范围检查的数值分页，保留旧修订。 */
    @Override
    public History history(String runId, int page, int size) {
        UserView actor = actor();
        authorized(runId, actor, false);
        pagination(page, size);
        DiagnosisOutcomeEntity outcome = find(runId);
        if (outcome == null) {
            return new History(List.of(), 0, page, size);
        }
        long total = revisions.selectCount(Wrappers.<DiagnosisOutcomeRevisionEntity>lambdaQuery()
                .eq(DiagnosisOutcomeRevisionEntity::getOutcomeId, outcome.getId()));
        List<Revision> values = revisions.page(outcome.getId(), (long) page * size, size)
                .stream().map(item -> view(item, actor)).toList();
        return new History(values, total, page, size);
    }

    /** 同键只能产生一次原子修订；未知响应读取或重试原键。 */
    @Override
    public RequestResult save(String runId, Save request) {
        UserView actor = actor();
        authorized(runId, actor, false);
        String digest = json.digest("SAVE", runId, request);
        return command(runId, request.requestId(), digest, actor, request);
    }

    /** 畸形字段只保留摘要，越权及无法识别键不进入业务表。 */
    @Override
    public RequestResult rejectInput(String runId, String requestId, String digest) {
        UserView actor = actor();
        authorized(runId, actor, false);
        return command(runId, requestId, digest, actor, null);
    }

    /** 请求查询先验证当前账号和来源读取权。 */
    @Override
    public RequestResult request(String runId, String requestId) {
        UserView actor = actor();
        authorized(runId, actor, false);
        DiagnosisOutcomeRequestEntity value = receipt(actor.id(), requestId);
        if (value == null || !runId.equals(value.getTargetId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "OUTCOME_REQUEST_NOT_FOUND");
        }
        return result(value, actor);
    }

    /** 发布与回填锁定相同来源，再读取当前不可变修订。 */
    @Override
    public Source source(String runId, boolean lock) {
        UserView actor = actor();
        if (!actor.isAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ADMIN_REQUIRED");
        }
        DiagnosisRun run = authorized(runId, actor, lock);
        DiagnosisOutcomeEntity outcome = lock ? outcomes.lock(runId) : find(runId);
        if (outcome == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "CASE_SOURCE_OUTCOME_REQUIRED");
        }
        DiagnosisOutcomeRevisionEntity revision = lock ? revisions.current(outcome.getCurrentRevisionId())
                : revisions.selectById(outcome.getCurrentRevisionId());
        return new Source(runId, run.question(), revision.getId(), revision.getRevisionNumber(),
                revision.getContentSha256(), revision.getConfirmedAt());
    }

    /** 持久化证据目录只供管理员，不改变执行或核查状态。 */
    @Override
    public List<RemediationViews.Detail> remediationOptions(String runId, int page, int size) {
        UserView actor = actor();
        authorized(runId, actor, false);
        return remediation.outcomeEvidence(runId, actor, page, size);
    }

    /** 先插入请求身份，唯一键竞争等待原事务；拒绝通过保存点回滚所有业务写入。 */
    private RequestResult command(String runId, String requestId, String digest, UserView actor, Save request) {
        if (requestId == null || !requestId.matches(OutcomeRequests.UUID_PATTERN)) {
            throw new IllegalArgumentException("INVALID_INPUT");
        }
        DiagnosisOutcomeRequestEntity existing = receipt(actor.id(), requestId);
        if (existing != null) {
            return replay(existing, runId, digest, actor);
        }
        try {
            DiagnosisOutcomeRequestEntity completed = transactions.execute(transaction -> {
                DiagnosisOutcomeRequestEntity entry = new DiagnosisOutcomeRequestEntity();
                entry.setId(UUID.randomUUID().toString());
                entry.setActorId(actor.id());
                entry.setRequestId(requestId);
                entry.setKind("SAVE");
                entry.setTargetId(runId);
                entry.setRequestDigest(digest);
                entry.setHttpStatus(0);
                entry.setCreatedAt(Instant.now().toString());
                requests.insert(entry);
                Object savepoint = transaction.createSavepoint();
                try {
                    if (request == null) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
                    }
                    append(runId, request, actor, entry);
                } catch (ResponseStatusException rejection) {
                    transaction.rollbackToSavepoint(savepoint);
                    entry.setHttpStatus(rejection.getStatusCode().value());
                    entry.setErrorCode(rejection.getReason());
                    entry.setResourceId(null);
                    entry.setRevisionId(null);
                } finally {
                    transaction.releaseSavepoint(savepoint);
                }
                requests.updateById(entry);
                return entry;
            });
            return result(Objects.requireNonNull(completed), actor);
        } catch (DuplicateKeyException conflict) {
            DiagnosisOutcomeRequestEntity original = receipt(actor.id(), requestId);
            if (original == null) {
                throw conflict;
            }
            return replay(original, runId, digest, actor);
        }
    }

    /** 全部条件在写入前检查，锁住来源和旧版本后追加修订。 */
    private void append(String runId, Save request, UserView actor, DiagnosisOutcomeRequestEntity receipt) {
        DiagnosisRun run = authorized(runId, actor, true);
        if (!TERMINAL.contains(run.status())) {
            reject("OUTCOME_RUN_NOT_TERMINAL");
        }
        DiagnosisOutcomeEntity outcome = outcomes.lock(runId);
        int version = outcome == null ? 0 : outcome.getVersion();
        if (version != request.expectedVersion()) {
            reject("OUTCOME_VERSION_CONFLICT");
        }
        if (version > 0 && blank(request.changeReason())) {
            reject("OUTCOME_CHANGE_REASON_REQUIRED");
        }
        List<EvidenceSnapshot> evidence = collect(runId, run.finishedAt(), request.evidence(), actor);
        boolean resolved = request.status() == OutcomeStatus.RESOLVED || request.status() == OutcomeStatus.PARTIALLY_RESOLVED;
        boolean verified = evidence.stream().anyMatch(item -> item.type() == OutcomeEvidenceType.MANUAL_OBSERVATION
                || ("PASSED".equals(item.verificationStatus()) && !"UNKNOWN".equals(item.executionStatus())
                    && "APPLIED".equals(item.applicationStatus())));
        if (resolved && (blank(request.actionsTaken()) || blank(request.verificationSummary()) || !verified)) {
            reject("OUTCOME_POST_VERIFICATION_REQUIRED");
        }
        if ((request.status() == OutcomeStatus.UNRESOLVED || request.status() == OutcomeStatus.PARTIALLY_RESOLVED)
                && blank(request.remainingIssue())) {
            reject("OUTCOME_REMAINING_ISSUE_REQUIRED");
        }
        if (request.status() == OutcomeStatus.UNCONFIRMED && blank(request.verificationSummary())) {
            reject("OUTCOME_VERIFICATION_GAP_REQUIRED");
        }
        String now = Instant.now().toString();
        if (outcome == null) {
            outcome = new DiagnosisOutcomeEntity();
            outcome.setId(UUID.randomUUID().toString());
            outcome.setRunId(runId);
            outcome.setVersion(0);
            outcome.setCreatedAt(now);
            outcome.setUpdatedAt(now);
            outcomes.insert(outcome);
        }
        Content content = new Content(request.status(), request.actionsTaken(), request.verificationSummary(),
                request.remainingIssue(), request.changeReason(), List.copyOf(evidence));
        DiagnosisOutcomeRevisionEntity revision = new DiagnosisOutcomeRevisionEntity();
        revision.setId(UUID.randomUUID().toString());
        revision.setOutcomeId(outcome.getId());
        revision.setRevisionNumber(version + 1);
        revision.setContentJson(json.write(content));
        revision.setContentSha256(FixedCommandJson.hash(revision.getContentJson()));
        revision.setActorId(actor.id());
        revision.setConfirmedAt(now);
        revisions.insert(revision);
        int updated = outcomes.update(null, Wrappers.<DiagnosisOutcomeEntity>lambdaUpdate()
                .eq(DiagnosisOutcomeEntity::getId, outcome.getId()).eq(DiagnosisOutcomeEntity::getVersion, version)
                .set(DiagnosisOutcomeEntity::getVersion, version + 1)
                .set(DiagnosisOutcomeEntity::getCurrentRevisionId, revision.getId())
                .set(DiagnosisOutcomeEntity::getUpdatedAt, now));
        if (updated != 1) {
            reject("OUTCOME_VERSION_CONFLICT");
        }
        receipt.setResourceId(outcome.getId());
        receipt.setRevisionId(revision.getId());
        receipt.setHttpStatus(200);
    }

    /** 证据仅引用当前来源；人员处理后观察的时间不得早于已有诊断终态时间。 */
    private List<EvidenceSnapshot> collect(String runId, String finishedAt, List<Evidence> inputs, UserView actor) {
        List<EvidenceSnapshot> result = new ArrayList<>();
        List<DiagnosisEvent> events = runs.eventsForOutcome(runId, actor);
        for (Evidence item : inputs) {
            if (item.type() == OutcomeEvidenceType.RUN_EVENT) {
                if (item.eventId() == null || item.remediationId() != null || !blank(item.verificationAction())
                        || !blank(item.observation()) || !blank(item.observedAt())) {
                    reject("OUTCOME_EVIDENCE_INVALID");
                }
                DiagnosisEvent event = events.stream().filter(value -> value.id() == item.eventId()).findFirst().orElse(null);
                if (event == null) {
                    reject("OUTCOME_EVIDENCE_INVALID");
                }
                result.add(new EvidenceSnapshot(item.type(), item.eventId(), null, item.summary(), null, null, null,
                        FixedCommandJson.hash(json.write(event)), null, null, null, null));
            } else if (item.type() == OutcomeEvidenceType.MANUAL_OBSERVATION) {
                if (item.eventId() != null || item.remediationId() != null || blank(item.verificationAction())
                        || blank(item.observation()) || blank(item.observedAt())) {
                    reject("OUTCOME_EVIDENCE_INVALID");
                }
                try {
                    Instant observed = Instant.parse(item.observedAt());
                    if (observed.isAfter(Instant.now().plusSeconds(300))
                            || (finishedAt != null && observed.isBefore(Instant.parse(finishedAt)))) {
                        reject("OUTCOME_EVIDENCE_INVALID");
                    }
                } catch (DateTimeParseException invalid) {
                    reject("OUTCOME_EVIDENCE_INVALID");
                }
                result.add(new EvidenceSnapshot(item.type(), null, null, item.summary(), item.verificationAction(),
                        item.observation(), item.observedAt(), null, null, null, null, null));
            } else {
                if (!actor.isAdmin() || item.remediationId() == null || item.eventId() != null
                        || !blank(item.verificationAction()) || !blank(item.observation()) || !blank(item.observedAt())) {
                    reject("OUTCOME_EVIDENCE_INVALID");
                }
                RemediationViews.Detail detail = remediation.get(item.remediationId());
                if (!runId.equals(detail.plan().sourceRunId())) {
                    reject("OUTCOME_EVIDENCE_INVALID");
                }
                Execution execution = detail.execution();
                result.add(new EvidenceSnapshot(item.type(), null, item.remediationId(), item.summary(), null, null, null,
                        FixedCommandJson.hash(json.write(execution)), execution == null ? "NOT_RUN" : execution.status(),
                        execution == null ? "NOT_STARTED" : execution.applicationStatus(),
                        execution == null ? "NOT_RUN" : execution.verificationStatus(), execution == null ? null : execution.receipt()));
            }
        }
        return result;
    }

    /** 原键正文改变明确拒绝，原回执保持不变。 */
    private RequestResult replay(DiagnosisOutcomeRequestEntity receipt, String target, String digest, UserView actor) {
        if (!target.equals(receipt.getTargetId()) || !digest.equals(receipt.getRequestDigest())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "REQUEST_CONTENT_CONFLICT");
        }
        return result(receipt, actor);
    }

    /** 原响应引用不可变修订，角色降权后重新投影。 */
    private RequestResult result(DiagnosisOutcomeRequestEntity value, UserView actor) {
        return new RequestResult(value.getRequestId(), value.getHttpStatus(), value.getErrorCode(), value.getTargetId(),
                value.getResourceId(), value.getRevisionId() == null ? null : revision(value.getRevisionId(), actor));
    }

    /** 读取指定不可变修订。 */
    private Revision revision(String id, UserView actor) {
        return view(Objects.requireNonNull(revisions.selectById(id)), actor);
    }

    /** 普通用户响应实际删去管理员字段，不能由浏览器隐藏代替权限。 */
    private Revision view(DiagnosisOutcomeRevisionEntity entity, UserView actor) {
        Content content = json.read(entity.getContentJson(), Content.class);
        List<EvidenceView> evidence = content.evidence().stream().map(item -> {
            UserEvidence limited = new UserEvidence(item.type(), item.eventId(), item.summary(), item.verificationAction(),
                    item.observation(), item.observedAt(), item.executionStatus(), item.applicationStatus(), item.verificationStatus());
            return actor.isAdmin() ? (EvidenceView) new AdminEvidence(limited, item.remediationId(), item.sourceHash(), item.receipt()) : limited;
        }).toList();
        return new Revision(entity.getId(), entity.getRevisionNumber(), content.status(), content.actionsTaken(),
                content.verificationSummary(), content.remainingIssue(), content.changeReason(), evidence,
                entity.getActorId(), entity.getConfirmedAt(), entity.getContentSha256());
    }

    /** 来源权限必须显式身份校验，历史无所属人只允许管理员。 */
    private DiagnosisRun authorized(String id, UserView actor, boolean lock) {
        if (id == null || !id.matches(OutcomeRequests.UUID_PATTERN)) {
            throw new IllegalArgumentException("INVALID_INPUT");
        }
        return runs.sourceForOutcome(id, actor, lock);
    }

    /** 空HTTP身份不能退化为内部管理员。 */
    private UserView actor() {
        UserView actor = CurrentUser.get();
        if (actor == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED");
        }
        return actor;
    }

    /** 通过唯一来源读取主记录。 */
    private DiagnosisOutcomeEntity find(String runId) {
        return outcomes.selectOne(Wrappers.<DiagnosisOutcomeEntity>lambdaQuery().eq(DiagnosisOutcomeEntity::getRunId, runId));
    }

    /** 请求命名空间包含服务器操作者。 */
    private DiagnosisOutcomeRequestEntity receipt(String actor, String requestId) {
        return requests.selectOne(Wrappers.<DiagnosisOutcomeRequestEntity>lambdaQuery()
                .eq(DiagnosisOutcomeRequestEntity::getActorId, actor).eq(DiagnosisOutcomeRequestEntity::getRequestId, requestId));
    }

    /** 有界分页参数不能拼入未经校验SQL。 */
    private void pagination(int page, int size) {
        if (page < 0 || page > 100000 || size < 1 || size > 100) {
            throw new IllegalArgumentException("INVALID_PAGINATION");
        }
    }

    /** 可选文字空值与空白等价。 */
    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /** 已受理的业务拒绝由事务保存稳定回执。 */
    private void reject(String code) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, code);
    }
}
