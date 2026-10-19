package io.supportops.integration.service;

import io.supportops.integration.dto.ConnectorAction;
import io.supportops.integration.dto.ConnectorCommand;
import io.supportops.integration.dto.ConnectorConfig;
import io.supportops.integration.vo.ConnectorView;
import io.supportops.integration.vo.SourceEnvelope;
import io.supportops.project.vo.TargetView;
import java.util.List;

/** 固定只读连接器的修订、测试及执行边界。 */
public interface ConnectorService {
    /** 管理员查询指定环境的全部修订和最近测试状态。 */
    List<ConnectorView> list(String environmentId);
    /** 创建不可变草稿及其独立凭据。 */
    ConnectorView create(ConnectorCommand command);
    /** 只读测试固定能力，不保存或返回事实正文。 */
    ConnectorView test(ConnectorAction command);
    /** 在环境排他锁内启用未过期的同修订测试。 */
    ConnectorView activate(ConnectorAction command);
    /** 读取私有策略；不得直接作为公开诊断上下文。 */
    ConnectorConfig policy(String revisionId);
    /** 按服务器认可目标访问固定能力并核对现场身份。 */
    SourceEnvelope read(String revisionId, TargetView target, String capability, String runId, int logLimit);
}
