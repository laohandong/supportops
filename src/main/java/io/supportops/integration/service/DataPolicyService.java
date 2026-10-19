package io.supportops.integration.service;

import io.supportops.integration.dto.DataPolicyCommand;
import io.supportops.integration.vo.DataPolicyView;

/** 系统资料对话与向量外发批准，独立于现场能力策略。 */
public interface DataPolicyService {
    /** 查询系统策略，未登记默认两项禁止。 */
    DataPolicyView policy(String systemId);
    /** 管理员显式批准并采用旧版本条件更新。 */
    DataPolicyView save(DataPolicyCommand command);
    /** 对话工具及模型外发前重新核对批准。 */
    void requireChat(String systemId);
    /** 索引、检索问题向量调用之前重新核对批准。 */
    void requireEmbedding(String systemId);
}
