package io.supportops.integration.service;

import io.supportops.integration.vo.ApplicationPrincipal;
import io.supportops.integration.vo.DiagnosticScope;
import io.supportops.user.vo.UserView;

/** 受理目标、历史范围与运行期间授权复核的业务边界。 */
public interface DiagnosticTargetService {
    /** 读取历史仍需当前授权；不以资源停用改写原始目标。 */
    boolean historyAllowed(String runId, String userId, boolean administrator);
    /** 使用服务端人员身份捕获目标和当前数据策略。 */
    DiagnosticScope user(String environmentId, UserView actor);
    /** 使用独立应用身份捕获目标，不赋予人员权限。 */
    DiagnosticScope application(String environmentId, ApplicationPrincipal actor);
    /** 检查当前授权及捕获策略交集；capability 为空仅复核身份。 */
    void check(DiagnosticScope scope, String capability);
    /** 保存一任务一条目标快照，与任务受理共用事务。 */
    void save(String runId, DiagnosticScope scope);
    /** 读取历史快照；历史推定只返回明确示例范围，不发起现场调用。 */
    DiagnosticScope snapshot(String runId);
}
