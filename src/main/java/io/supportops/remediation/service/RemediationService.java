package io.supportops.remediation.service;

import io.supportops.remediation.dto.RemediationRequests.Command;
import io.supportops.remediation.dto.RemediationRequests.Create;
import io.supportops.remediation.vo.RemediationViews.Action;
import io.supportops.remediation.vo.RemediationViews.Detail;
import io.supportops.remediation.vo.RemediationViews.Event;
import io.supportops.remediation.vo.RemediationViews.Page;
import io.supportops.remediation.vo.RemediationViews.Preview;
import io.supportops.remediation.vo.RemediationViews.Report;
import io.supportops.remediation.vo.RemediationViews.RequestResult;
import java.util.List;
import io.supportops.user.vo.UserView;

/** 人工审批与受控执行边界；所有命令重新校验服务端身份。 */
public interface RemediationService {
    /** 只读投影持久化修复证据；不执行、核查或发验证HTTP。 */
    List<Detail> outcomeEvidence(String sourceRunId, UserView actor, int page, int size);

    /** 固定目录不调用模型。 */
    List<Action> actions();
    /** 从当前现场只读预检查。 */
    Preview preview(String actionId);
    /** 创建不可变提案。 */
    Detail create(Create request);
    /** 将可识别原请求的输入拒绝持久化；不能覆写同编号的已受理事实。 */
    void rejectInvalidInput(String requestId, String kind, String target, String digest);
    /** 分页筛选提案，不隐式执行或刷新现场。 */
    Page list(String status, int page, int size);
    /** 读取详情与能力提示。 */
    Detail get(String id);
    /** 单管理员批准，不自动执行。 */
    Detail approve(String id, Command request);
    /** 驳回必须说明理由。 */
    Detail reject(String id, Command request);
    /** 撤销尚未消费的提案。 */
    Detail cancel(String id, Command request);
    /** 在共享准入边界内一次受理、应用和验证。 */
    Detail execute(String id, Command request);
    /** 查询当前操作者的固定请求；拒绝结果也可查询。 */
    RequestResult request(String requestId);
    /** 读取递增的追加审计游标。 */
    List<Event> events(String id, long after);
    /** 仅核查原操作，不再次写配置或发送业务请求。 */
    Detail reconcile(String executionId, Command request);
    /** 确認原处理器退出后结束未决记录，保留不明事实。 */
    Detail closeUnknown(String executionId, Command request);
    /** 报告只读取持久化证据。 */
    Report report(String id);
}
