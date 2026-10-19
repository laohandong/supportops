package io.supportops.outcome.service;

import io.supportops.outcome.dto.OutcomeRequests.Save;
import io.supportops.outcome.vo.OutcomeViews.Detail;
import io.supportops.outcome.vo.OutcomeViews.History;
import io.supportops.outcome.vo.OutcomeViews.RequestResult;
import io.supportops.outcome.vo.OutcomeViews.Source;
import io.supportops.remediation.vo.RemediationViews;
import java.util.List;

/** 人员确认事实管理；通过来源服务校验权限，不修改诊断调度或模型输入。 */
public interface DiagnosisOutcomeService {
    /** 无记录返回明确空结果。 */
    Detail get(String runId);
    /** 不可变历史按修订倒序分页。 */
    History history(String runId, int page, int size);
    /** 固定命令原子追加处理修订和终局回执。 */
    RequestResult save(String runId, Save request);
    /** 只读取当前操作者的原请求。 */
    RequestResult request(String runId, String requestId);
    /** 有权且可识别请求的输入拒绝保存稳定回执。 */
    RequestResult rejectInput(String runId, String requestId, String digest);
    /** 案例来源最小投影；lock只在同一短事务内使用。 */
    Source source(String runId, boolean lock);
    /** 管理员读取关联修复持久化事实，不触发核查。 */
    List<RemediationViews.Detail> remediationOptions(String runId, int page, int size);
}
