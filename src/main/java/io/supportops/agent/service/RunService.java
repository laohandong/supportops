package io.supportops.agent.service;

import io.supportops.agent.vo.DiagnosisEvent;
import io.supportops.agent.vo.DiagnosisRun;
import io.supportops.user.vo.UserView;
import io.supportops.integration.vo.DiagnosticScope;
import io.supportops.integration.vo.ApplicationPrincipal;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.function.Supplier;

/** 管理持久化诊断任务与单个可变示例环境的执行准入。 */
public interface RunService {
    /** 仅允许具有明确内置示例目标的诊断作为示例修复来源。 */
    default void requireDemoRemediationSource(String runId) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "REMEDIATION_SOURCE_TARGET_UNCONFIRMED");
    }
    /** 处理结果来源必须有明确身份；锁定仅由实现支持的同一短事务使用。 */
    default DiagnosisRun sourceForOutcome(String id, UserView actor, boolean lock) {
        if (actor == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED");
        }
        return actor.isAdmin() ? get(id) : getOwned(id, actor.id());
    }

    /** 明确授权后读取来源事件，不改变游标、回答或用量。 */
    default List<DiagnosisEvent> eventsForOutcome(String id, UserView actor) {
        sourceForOutcome(id, actor, false);
        return events(id, 0);
    }

    /** 校验并受理诊断；返回快照不代表模型已经执行完成。 */
    DiagnosisRun start(String question, String sessionId, boolean lexicalOnly);
    /** 显式环境受理，旧实现仅支持未指定环境的示例入口。 */
    default DiagnosisRun startTarget(String question, String sessionId, boolean lexicalOnly, String environmentId) {
        if (environmentId != null) { throw new ResponseStatusException(HttpStatus.CONFLICT, "TARGET_NOT_SUPPORTED"); }
        return start(question, sessionId, lexicalOnly);
    }
    /** 应用受理由固定请求服务提供事务，提交后才调度。 */
    default DiagnosisRun startApplication(String id, String question, boolean lexicalOnly, ApplicationPrincipal actor, DiagnosticScope scope) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "APPLICATION_NOT_SUPPORTED");
    }
    /** 明确应用归属后读取快照，不能依赖人员请求上下文。 */
    default DiagnosisRun getApplication(String id, ApplicationPrincipal actor) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "RUN_NOT_FOUND");
    }
    /** 应用读取有界持久化事件。 */
    default List<DiagnosisEvent> eventsApplication(String id, ApplicationPrincipal actor, long after, int limit) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "RUN_NOT_FOUND");
    }
    /** 返回安全事实编号，避免为终态结果加载全部流式片段。 */
    default List<Long> evidenceIdsApplication(String id, ApplicationPrincipal actor) { return List.of(); }
    /** 区分报告用量为零与从未收到用量。 */
    default boolean usageReportedApplication(String id, ApplicationPrincipal actor) { return false; }
    /** 取消只影响本应用任务。 */
    default DiagnosisRun cancelApplication(String id, ApplicationPrincipal actor) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "RUN_NOT_FOUND");
    }
    /** 配置和运维操作保护全部环境的执行资源。 */
    default <T> T whenGloballyIdle(Supplier<T> operation) { return whenIdle(operation); }

    /** 可信渠道使用绑定身份和固定请求编号受理独立诊断，检索遵循统一配置；重复编号只读取原任务。 */
    DiagnosisRun startExternal(String requestId, String question, UserView owner);
    /** 可信渠道显式使用管理员指定的默认环境，不从消息内容猜测目标。 */
    default DiagnosisRun startExternalTarget(String requestId, String question, UserView owner, String environmentId) {
        return startExternal(requestId, question, owner);
    }

    /** 渠道读取必须严格匹配任务所属人，管理员渠道身份也不扩大读取范围。 */
    DiagnosisRun getOwned(String id, String userId);

    /** 当前有任务尚未释放执行资源时拒绝操作。 */
    void requireIdle();

    /** 在与诊断准入相同的互斥边界内执行人工操作，避免检查后再修改的竞争。 */
    <T> T whenIdle(Supplier<T> operation);

    /** 请求取消任务；重复取消已结束任务时保留原终态。 */
    DiagnosisRun cancel(String id);

    /** 逻辑归档指定任务所属的整个对话，重复请求不改变归档时间或计量。 */
    void archive(String id);

    /** 按创建时间倒序读取最近一百条任务。 */
    List<DiagnosisRun> list();

    /** 读取任务，不存在时返回明确的未找到错误。 */
    DiagnosisRun get(String id);

    /** 按递增游标读取该任务中编号大于 after 的事件。 */
    List<DiagnosisEvent> events(String id, long after);
}
