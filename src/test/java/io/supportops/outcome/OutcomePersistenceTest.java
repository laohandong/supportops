package io.supportops.outcome;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.supportops.agent.service.RunService;
import io.supportops.agent.vo.DiagnosisRun;
import io.supportops.api.support.FixedCommandJson;
import io.supportops.outcome.constant.OutcomeStatus;
import io.supportops.outcome.dto.OutcomeRequests.Save;
import io.supportops.outcome.mapper.DiagnosisOutcomeMapper;
import io.supportops.outcome.mapper.DiagnosisOutcomeRequestMapper;
import io.supportops.outcome.mapper.DiagnosisOutcomeRevisionMapper;
import io.supportops.outcome.service.impl.DiagnosisOutcomeServiceImpl;
import io.supportops.outcome.vo.OutcomeViews.RequestResult;
import io.supportops.persistence.MapperTestDatabase;
import io.supportops.remediation.service.RemediationService;
import io.supportops.user.CurrentUser;
import io.supportops.user.vo.UserView;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** 真实随机MySQL验证原子修订、固定拒绝回执与并发；独立JDBC核对业务结果。 */
class OutcomePersistenceTest {
    /** 同键两个线程只产生一条修订，业务拒绝不能留下半条记录。 */
    @Test
    void concurrentRequestsAndRejectedTransaction() throws Exception {
        try (MapperTestDatabase database = new MapperTestDatabase();
                ValidatorFactory validation = Validation.buildDefaultValidatorFactory();
                ExecutorService workers = Executors.newFixedThreadPool(2)) {
            String actorId = UUID.randomUUID().toString();
            String runId = UUID.randomUUID().toString();
            String now = Instant.now().toString();
            database.jdbc.update("INSERT INTO app_users(id,username,password_hash,role,created_at) VALUES(?,?,?,?,?)",
                    actorId, "oc_test_admin", "synthetic-not-a-login-hash", "ADMIN", now);
            database.jdbc.update("INSERT INTO runs(id,session_id,question,status,answer,error_code,created_at,finished_at,user_id) VALUES(?,?,?,?,?,?,?,?,?)",
                    runId, UUID.randomUUID().toString(), "合成诊断", "COMPLETED", "原回答必须不变", "", now, now, actorId);
            UserView actor = new UserView(actorId, "oc_test_admin", "ADMIN", now);
            RunService runs = mock(RunService.class);
            DiagnosisRun source = new DiagnosisRun(runId, UUID.randomUUID().toString(), "合成诊断", "COMPLETED",
                    "原回答必须不变", "", now, now, 0, 0, 0, actorId);
            when(runs.sourceForOutcome(anyString(), any(UserView.class), anyBoolean())).thenAnswer(call -> {
                if (Boolean.TRUE.equals(call.getArgument(2))) {
                    database.runs.lockOutcomeSource(call.getArgument(0));
                }
                return source;
            });
            FixedCommandJson json = new FixedCommandJson(new ObjectMapper(), validation.getValidator());
            DiagnosisOutcomeServiceImpl service = new DiagnosisOutcomeServiceImpl(database.mapper(DiagnosisOutcomeMapper.class),
                    database.mapper(DiagnosisOutcomeRevisionMapper.class), database.mapper(DiagnosisOutcomeRequestMapper.class),
                    runs, mock(RemediationService.class), database.transactions, json);
            Save command = new Save(UUID.randomUUID().toString(), 0, OutcomeStatus.UNCONFIRMED,
                    "尚未操作", "等待人工观察", "待验证", "", List.of());
            Callable<RequestResult> submit = () -> {
                authenticate(actor);
                try { return service.save(runId, command); }
                finally { RequestContextHolder.resetRequestAttributes(); }
            };
            List<Future<RequestResult>> results = workers.invokeAll(List.of(submit, submit));
            assertThat(results.get(0).get()).isEqualTo(results.get(1).get());
            assertThat(database.jdbc.queryForObject("SELECT COUNT(*) FROM diagnosis_outcome_revisions", Integer.class)).isEqualTo(1);
            authenticate(actor);
            Save changed = new Save(command.requestId(), 0, OutcomeStatus.UNCONFIRMED, "正文变化", "等待观察", "待验证", "", List.of());
            assertThatThrownBy(() -> service.save(runId, changed)).hasMessageContaining("REQUEST_CONTENT_CONFLICT");
            Save invalid = new Save(UUID.randomUUID().toString(), 1, OutcomeStatus.RESOLVED,
                    "配置已应用", "无业务验证", "", "新增验证", List.of());
            RequestResult refusal = service.save(runId, invalid);
            assertThat(refusal.httpStatus()).isEqualTo(409);
            assertThat(service.save(runId, invalid)).isEqualTo(refusal);
            assertThat(database.jdbc.queryForObject("SELECT COUNT(*) FROM diagnosis_outcome_revisions", Integer.class)).isEqualTo(1);
            assertThat(database.jdbc.queryForObject("SELECT version FROM diagnosis_outcomes", Integer.class)).isEqualTo(1);
            assertThat(database.jdbc.queryForObject("SELECT answer FROM runs WHERE id=?", String.class, runId)).isEqualTo("原回答必须不变");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    /** 严格拒绝身份伪造与重复属性，Java与浏览器采用相同Unicode规范。 */
    @Test
    void strictInputAndCanonicalDigest() {
        try (ValidatorFactory validation = Validation.buildDefaultValidatorFactory()) {
            FixedCommandJson json = new FixedCommandJson(new ObjectMapper(), validation.getValidator());
            assertThat(json.write(new ObjectMapper().createObjectNode().put("z", " e\u0301\r\n ").put("a", "中文")))
                    .isEqualTo("{\"a\":\"中文\",\"z\":\"é\"}");
            String id = UUID.randomUUID().toString();
            String body = "{\"requestId\":\"" + id + "\",\"expectedVersion\":0,\"status\":\"UNCONFIRMED\",\"actionsTaken\":\"无\",\"evidence\":[],\"ownerId\":\"forged\"}";
            assertThatThrownBy(() -> json.decode(body, Save.class)).hasMessage("INVALID_INPUT");
            assertThatThrownBy(() -> json.decode("{\"requestId\":\"" + id + "\",\"requestId\":\"" + id + "\"}", Save.class)).hasMessage("INVALID_INPUT");
            assertThat(json.field("{\"sourceRunId\":\"Authorization: secret\"}", "sourceRunId", "INVALID")).isEqualTo("INVALID");
        }
    }

    /** 各线程使用独立请求身份，空身份不允许绕过权限。 */
    private static void authenticate(UserView user) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(CurrentUser.ATTRIBUTE, user);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
