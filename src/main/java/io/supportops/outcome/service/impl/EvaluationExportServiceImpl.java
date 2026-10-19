package io.supportops.outcome.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import io.supportops.api.support.FixedCommandJson;
import io.supportops.outcome.dto.EvaluationExportRequest;
import io.supportops.outcome.entity.OutcomeExportEntity;
import io.supportops.outcome.entity.OutcomeExportRequestEntity;
import io.supportops.outcome.mapper.OutcomeExportInstallationMapper;
import io.supportops.outcome.mapper.OutcomeExportMapper;
import io.supportops.outcome.mapper.OutcomeExportRequestMapper;
import io.supportops.outcome.service.DiagnosisOutcomeService;
import io.supportops.outcome.service.EvaluationExportService;
import io.supportops.outcome.vo.OutcomeViews;
import io.supportops.source.service.SourcePackageCodec;
import io.supportops.source.vo.SourcePackage;
import io.supportops.user.CurrentUser;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

/** 人工脱敏导出边界；短事务锁定来源修订并保存不可变正文与固定回执。 */
@Service
public class EvaluationExportServiceImpl implements EvaluationExportService {
    private final OutcomeExportMapper exports;
    private final OutcomeExportRequestMapper requests;
    private final OutcomeExportInstallationMapper installation;
    private final DiagnosisOutcomeService outcomes;
    private final FixedCommandJson json;
    private final SourcePackageCodec codec;
    private final TransactionTemplate transactions;

    /** 注入来源服务与本模块Mapper，不跨模块直接访问诊断表。 */
    public EvaluationExportServiceImpl(
            OutcomeExportMapper exports,
            OutcomeExportRequestMapper requests,
            OutcomeExportInstallationMapper installation,
            DiagnosisOutcomeService outcomes,
            FixedCommandJson json,
            SourcePackageCodec codec,
            TransactionTemplate transactions) {
        this.exports = exports;
        this.requests = requests;
        this.installation = installation;
        this.outcomes = outcomes;
        this.json = json;
        this.codec = codec;
        this.transactions = transactions;
    }

    /** 成功与业务拒绝均保存原请求结果，未知响应可用原键恢复。 */
    public Receipt create(String runId, EvaluationExportRequest input) {
        String actor = admin();
        String digest = json.digest("EVALUATION_EXPORT", runId, input);
        return transactions.execute(
                status -> {
                    String system = installation.lock();
                    OutcomeExportRequestEntity previous = receiptEntity(actor, input.requestId());
                    if (previous != null) {
                        if (!digest.equals(previous.getRequestDigest())) {
                            throw conflict("REQUEST_CONTENT_CONFLICT");
                        }
                        return view(previous);
                    }
                    if (system == null) {
                        system = UUID.randomUUID().toString();
                        installation.initialize(system);
                    }
                    OutcomeExportRequestEntity receipt = new OutcomeExportRequestEntity();
                    receipt.setId(UUID.randomUUID().toString());
                    receipt.setActorId(actor);
                    receipt.setRequestId(input.requestId());
                    receipt.setRequestDigest(digest);
                    receipt.setCreatedAt(Instant.now().toString());
                    try {
                        if (!input.redactionConfirmed()) {
                            throw conflict("SOURCE_REDACTION_CONFIRMATION_REQUIRED");
                        }
                        OutcomeViews.Source source = outcomes.source(runId, true);
                        if (!source.revisionId().equals(input.expectedOutcomeRevisionId())) {
                            throw conflict("SOURCE_REVISION_CHANGED");
                        }
                        OutcomeViews.Detail detail = outcomes.get(runId);
                        if (!detail.canSave()) {
                            throw conflict("SOURCE_RUN_NOT_TERMINAL");
                        }
                        String id = UUID.randomUUID().toString();
                        String now = receipt.getCreatedAt();
                        SourcePackage.Content content =
                                new SourcePackage.Content(
                                        input.redactedQuestion(),
                                        input.redactedSummary(),
                                        detail.outcome().status().name());
                        SourcePackage provisional =
                                new SourcePackage(
                                        1,
                                        id,
                                        system,
                                        runId,
                                        source.revisionId(),
                                        now,
                                        content,
                                        now,
                                        "");
                        SourcePackage pack =
                                new SourcePackage(
                                        1,
                                        id,
                                        system,
                                        runId,
                                        source.revisionId(),
                                        now,
                                        content,
                                        now,
                                        codec.digest(provisional));
                        codec.validate(pack);
                        OutcomeExportEntity exported = new OutcomeExportEntity();
                        exported.setId(id);
                        exported.setSourceRunId(runId);
                        exported.setOutcomeRevisionId(source.revisionId());
                        exported.setActorId(actor);
                        exported.setPackageJson(json.write(pack));
                        exported.setContentSha256(pack.contentSha256());
                        exported.setCreatedAt(now);
                        exports.insert(exported);
                        receipt.setExportId(id);
                        receipt.setHttpStatus(201);
                    } catch (ResponseStatusException rejected) {
                        receipt.setHttpStatus(rejected.getStatusCode().value());
                        receipt.setErrorCode(rejected.getReason());
                    }
                    requests.insert(receipt);
                    return view(receipt);
                });
    }

    /** 无法解码的正文只保存请求身份与摘要，刷新后仍能取回同一个终局拒绝。 */
    @Override
    public Receipt reject(FixedCommandJson.Rejected rejected) {
        if (rejected == null) {
            throw new IllegalArgumentException("INVALID_INPUT");
        }
        String actor = admin();
        return transactions.execute(
                status -> {
                    installation.lock();
                    OutcomeExportRequestEntity previous =
                            receiptEntity(actor, rejected.requestId());
                    if (previous != null) {
                        if (!rejected.digest().equals(previous.getRequestDigest())) {
                            throw conflict("REQUEST_CONTENT_CONFLICT");
                        }
                        return view(previous);
                    }
                    OutcomeExportRequestEntity receipt = new OutcomeExportRequestEntity();
                    receipt.setId(UUID.randomUUID().toString());
                    receipt.setActorId(actor);
                    receipt.setRequestId(rejected.requestId());
                    receipt.setRequestDigest(rejected.digest());
                    receipt.setCreatedAt(Instant.now().toString());
                    receipt.setHttpStatus(400);
                    receipt.setErrorCode("INVALID_INPUT");
                    requests.insert(receipt);
                    return view(receipt);
                });
    }

    /** 历史读取只查持久化包，不重新依赖原诊断的可见性或当前修订。 */
    public SourcePackage get(String id) {
        admin();
        OutcomeExportEntity value = exports.selectById(id);
        if (value == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "SOURCE_EXPORT_NOT_FOUND");
        }
        return json.read(value.getPackageJson(), SourcePackage.class);
    }

    /** 回执仅在当前操作者命名空间查询。 */
    public Receipt request(String id) {
        OutcomeExportRequestEntity value = receiptEntity(admin(), id);
        if (value == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "SOURCE_REQUEST_NOT_FOUND");
        }
        return view(value);
    }

    /** 返回当前管理员身份，禁止浏览器指定操作者。 */
    private String admin() {
        if (CurrentUser.get() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED");
        }
        if (!CurrentUser.get().isAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ADMIN_REQUIRED");
        }
        return CurrentUser.get().id();
    }

    /** 按操作者和固定键查询唯一回执。 */
    private OutcomeExportRequestEntity receiptEntity(String actor, String id) {
        return requests.selectOne(
                Wrappers.<OutcomeExportRequestEntity>lambdaQuery()
                        .eq(OutcomeExportRequestEntity::getActorId, actor)
                        .eq(OutcomeExportRequestEntity::getRequestId, id));
    }

    /** 输出原状态和原包，后续来源变化不覆盖已保存正文。 */
    private Receipt view(OutcomeExportRequestEntity row) {
        return new Receipt(
                row.getRequestId(),
                row.getHttpStatus(),
                row.getErrorCode(),
                row.getExportId(),
                row.getExportId() == null ? null : get(row.getExportId()));
    }

    /** 生成受控冲突，错误正文不包含导出内容。 */
    private ResponseStatusException conflict(String code) {
        return new ResponseStatusException(HttpStatus.CONFLICT, code);
    }
}
