package io.supportops.integration.service;

import io.supportops.integration.dto.OpenDiagnosisCommand;
import io.supportops.integration.vo.ApplicationPrincipal;
import io.supportops.integration.vo.OpenViews;
import io.supportops.project.vo.TargetView;
import java.util.List;

/** 应用固定请求受理和持久化安全结果读取。 */
public interface OpenDiagnosisService {
    /** 查询应用当前可用目标，不返回私有地址。 */
    List<TargetView> environments(ApplicationPrincipal actor);
    /** 同键同参重放，同键异参冲突；受理事务提交后执行。 */
    OpenViews.Receipt submit(ApplicationPrincipal actor, OpenDiagnosisCommand command);
    /** 响应丢失时查询固定请求，无记录返回404。 */
    OpenViews.Receipt request(ApplicationPrincipal actor, String requestId);
    /** 查询本应用诊断快照。 */
    OpenViews.Diagnosis diagnosis(ApplicationPrincipal actor, String runId);
    /** 按游标返回安全有界事件页。 */
    OpenViews.EventPage events(ApplicationPrincipal actor, String runId, long after, int limit);
    /** 获取已持久化证据，不重读现场。 */
    OpenViews.Evidence evidence(ApplicationPrincipal actor, String runId, long eventId);
    /** 读取终态结果，非终态返回409。 */
    OpenViews.Result result(ApplicationPrincipal actor, String runId);
    /** 取消本应用任务，重复请求保留既有终态。 */
    OpenViews.Diagnosis cancel(ApplicationPrincipal actor, String runId);
}
