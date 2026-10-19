package io.supportops.demo.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.supportops.agent.service.RunService;
import io.supportops.demo.entity.DemoEnvironmentEventEntity;
import io.supportops.demo.mapper.DemoEnvironmentEventMapper;
import io.supportops.demo.service.DemoScenarioService;
import io.supportops.demo.vo.DemoEnvironmentEvent;
import io.supportops.demo.vo.DemoState;
import io.supportops.remediation.constant.RemediationContract;
import io.supportops.remediation.service.support.RemediationJson;
import io.supportops.remediation.vo.RemediationViews.Snapshot;
import io.supportops.user.CurrentUser;
import io.supportops.user.service.UserService;
import io.supportops.user.vo.UserView;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** 先提交操作意图再改变内存现场，结果失败保留可审阅原记录，不自动重做。 */
@Service
public final class DemoScenarioServiceImpl implements DemoScenarioService {
    private final DemoEnvironment environment;
    private final RunService runs;
    private final UserService users;
    private final DemoEnvironmentEventMapper mapper;
    private final TransactionTemplate transactions;
    private final RemediationJson json;

    /** 注入正式环境、共享准入、身份与短事务持久化组件。 */
    public DemoScenarioServiceImpl(DemoEnvironment environment, RunService runs, UserService users,
            DemoEnvironmentEventMapper mapper, TransactionTemplate transactions, RemediationJson json) {
        this.environment = environment;
        this.runs = runs;
        this.users = users;
        this.mapper = mapper;
        this.transactions = transactions;
        this.json = json;
    }

    /** 校验人工权限和固定场景，全部变更均通过诊断共用准入。 */
    @Override
    public DemoState configure(String scenario) {
        String actor = actor();
        if (!Set.of("healthy", "unavailable", "credentials", "migration", "stale-docs", "insufficient", "injection").contains(scenario)) {
            throw new IllegalArgumentException("INVALID_INPUT");
        }
        return runs.whenIdle(() -> change(scenario, actor));
    }

    /** HTTP在数据库事务之外；审计意图未提交时绝不改变环境。 */
    private DemoState change(String scenario, String actor) {
        Snapshot before = environment.snapshot();
        DemoState oldState = environment.operatorView();
        DemoEnvironmentEventEntity event = new DemoEnvironmentEventEntity();
        event.setActorId(actor);
        event.setTargetId(RemediationContract.TARGET);
        event.setInstanceId(before.instanceId());
        event.setKind("SCENARIO_RESET");
        event.setScenarioBefore(oldState.scenario());
        event.setScenarioAfter(scenario);
        event.setBeforeRevision(before.revision());
        event.setBeforeJson(json.write(new Facts(before, oldState)));
        event.setStatus("STARTED");
        event.setErrorCode("");
        event.setStartedAt(Instant.now().toString());
        transactions.executeWithoutResult(transaction -> single(mapper.insert(event)));
        try {
            DemoState result = environment.configure(scenario);
            Snapshot after = environment.snapshot();
            finish(event, after, result, "APPLIED", "");
            return result;
        } catch (RuntimeException exception) {
            // 无法保存结果时不假定回滚了内存变化，原STARTED记录是未完成持久化的证据。
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "SCENARIO_RESULT_NOT_PERSISTED", exception);
        }
    }

    /** 条件更新并核对实例和递增修订；异常读回不能包装为成功。 */
    private void finish(DemoEnvironmentEventEntity event, Snapshot after, DemoState state, String status, String error) {
        boolean matches = event.getInstanceId().equals(after.instanceId())
                && after.revision() == event.getBeforeRevision() + 1
                && event.getScenarioAfter().equals(state.scenario());
        transactions.executeWithoutResult(transaction -> single(mapper.update(Wrappers.<DemoEnvironmentEventEntity>lambdaUpdate()
                .eq(DemoEnvironmentEventEntity::getId, event.getId()).eq(DemoEnvironmentEventEntity::getStatus, "STARTED")
                .set(DemoEnvironmentEventEntity::getAfterRevision, after.revision())
                .set(DemoEnvironmentEventEntity::getAfterJson, json.write(new Facts(after, state)))
                .set(DemoEnvironmentEventEntity::getStatus, matches ? status : "UNKNOWN")
                .set(DemoEnvironmentEventEntity::getErrorCode, matches ? error : "SCENARIO_READBACK_MISMATCH")
                .set(DemoEnvironmentEventEntity::getFinishedAt, Instant.now().toString()))));
    }

    /** 分页历史只查询持久化记录，任何读取都不会设置场景或发送同步请求。 */
    @Override
    public List<DemoEnvironmentEvent> events(long after) {
        actor();
        if (after < 0) {
            throw new IllegalArgumentException("INVALID_INPUT");
        }
        return mapper.selectList(Wrappers.<DemoEnvironmentEventEntity>lambdaQuery()
                .eq(DemoEnvironmentEventEntity::getTargetId, RemediationContract.TARGET)
                .gt(DemoEnvironmentEventEntity::getId, after).orderByAsc(DemoEnvironmentEventEntity::getId)
                .last("LIMIT 200")).stream().map(event -> new DemoEnvironmentEvent(event.getId(), event.getActorId(),
                        event.getTargetId(), event.getInstanceId(), event.getKind(), event.getScenarioBefore(), event.getScenarioAfter(),
                        event.getBeforeRevision(), event.getAfterRevision(), event.getBeforeJson(), event.getAfterJson(), event.getStatus(),
                        event.getErrorCode(), event.getStartedAt(), event.getFinishedAt())).toList();
    }

    /** 服务端重新读取角色，不能仅依靠HTTP过滤器或客户端身份。 */
    private String actor() {
        UserView user = CurrentUser.id() == null ? null : users.find(CurrentUser.id());
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED");
        }
        if (!"ADMIN".equals(user.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ADMIN_REQUIRED");
        }
        return user.id();
    }

    /** 所有审计写入必须恰好改变一行。 */
    private static void single(int count) {
        if (count != 1) {
            throw new IllegalStateException("SCENARIO_AUDIT_CONFLICT");
        }
    }

    /** 有界协议证据包含现场身份、修订、提供配置、生效配置及真实同步结果。 */
    private record Facts(Snapshot snapshot, DemoState state) {}
}
