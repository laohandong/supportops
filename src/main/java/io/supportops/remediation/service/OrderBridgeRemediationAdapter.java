package io.supportops.remediation.service;

import io.supportops.remediation.vo.RemediationViews.Snapshot;
import io.supportops.remediation.vo.RemediationViews.Receipt;
import java.util.Map;

/** 内置目标的固定能力；不向 MCP 注册，不接受任意键、地址或验证策略。 */
public interface OrderBridgeRemediationAdapter {
    /** 只读实例身份，不等待业务 HTTP 的环境锁。 */
    String instanceId();
    /** 读取现场；不发业务请求。 */
    Snapshot snapshot();
    /** 受理前预留有界回执空间，并登记在途；失败不得应用。 */
    void reserve(String operationId, String verificationRequestId, Snapshot before);
    /** 在同一环境临界区核对并应用固定变换，重复操作不再次写入。 */
    void apply(String operationId);
    /** 对原操作至多发一次新业务请求；已开始而响应不明时不重发。 */
    void verify(String operationId);
    /** 只读取原操作回执；不存在不代表未应用。 */
    Receipt receipt(String operationId);
    /** 处理器退出，保留未落库证据。 */
    void exited(String operationId);
    /** 仅已完整落库的终态回收容量。 */
    void release(String operationId);
    /** 判断本实例操作仍在途，不以超时推定退出。 */
    boolean inFlight(String operationId);
    /** 固定变换保留无关键，删除 orders.path 并增加新键。 */
    Map<String, String> transformed(Snapshot before);
}
