package io.supportops.integration.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.supportops.integration.dto.DataPolicyCommand;
import io.supportops.integration.entity.SystemDataPolicyEntity;
import io.supportops.integration.mapper.SystemDataPolicyMapper;
import io.supportops.integration.service.DataPolicyService;
import io.supportops.integration.service.support.IntegrationInputs;
import io.supportops.integration.service.support.ManagementCommands;
import io.supportops.integration.vo.DataPolicyView;
import io.supportops.project.service.ProjectService;
import java.time.Instant;
import org.springframework.stereotype.Service;

/** 审批回执与条件更新同事务，缺失配置永远不自动开放资料外发。 */
@Service
public class DataPolicyServiceImpl implements DataPolicyService {
    private final SystemDataPolicyMapper policies;
    private final ManagementCommands commands;
    private final ProjectService projects;
    private io.supportops.integration.service.support.RestoreGeneration restoreGeneration;
    /** 注入恢复代次，旧独立服务测试使用初始代次零。 */
    @org.springframework.beans.factory.annotation.Autowired
    public void restoreGeneration(io.supportops.integration.service.support.RestoreGeneration value) { restoreGeneration = value; }
    /** 返回人工批准所属的当前代次。 */
    private int generation() { return restoreGeneration == null ? 0 : restoreGeneration.current(); }
    /** 注入策略存储和项目业务边界。 */
    public DataPolicyServiceImpl(SystemDataPolicyMapper policies, ManagementCommands commands, ProjectService projects) {
        this.policies = policies; this.commands = commands; this.projects = projects;
    }
    /** 默认策略支持资料管理和关键词检索，不授权网络外发。 */
    @Override public DataPolicyView policy(String systemId) {
        SystemDataPolicyEntity value = policies.selectById(IntegrationInputs.uuid(systemId));
        return value == null ? new DataPolicyView(systemId, false, false, 0)
                : new DataPolicyView(systemId, java.util.Objects.equals(value.getApprovedGeneration(), generation()) && value.getChatAllowed(),
                        java.util.Objects.equals(value.getApprovedGeneration(), generation()) && value.getEmbeddingAllowed(), value.getVersion());
    }
    /** 只给可用系统设置批准，旧版本不匹配则保留原策略。 */
    @Override public DataPolicyView save(DataPolicyCommand input) {
        return commands.execute(input.requestId(), "DATA_POLICY_SAVE", input, DataPolicyView.class, () -> {
            String system = IntegrationInputs.uuid(input.systemId());
            if (projects.resources().stream().noneMatch(value -> system.equals(value.id()) && "SYSTEM".equals(value.kind()) && "ACTIVE".equals(value.status()))) {
                throw IntegrationInputs.error(404, "SYSTEM_NOT_AVAILABLE");
            }
            SystemDataPolicyEntity previous = policies.selectById(system);
            if (previous == null) {
                if (input.expectedVersion() != 0) { throw IntegrationInputs.error(409, "DATA_POLICY_VERSION_CONFLICT"); }
                SystemDataPolicyEntity value = new SystemDataPolicyEntity(); value.setSystemId(system); value.setVersion(1);
                value.setApprovedGeneration(generation());
                value.setChatAllowed(input.chatAllowed()); value.setEmbeddingAllowed(input.embeddingAllowed()); value.setUpdatedAt(Instant.now().toString()); policies.insert(value);
            } else {
                int changed = policies.update(Wrappers.<SystemDataPolicyEntity>lambdaUpdate().eq(SystemDataPolicyEntity::getSystemId, system)
                        .eq(SystemDataPolicyEntity::getVersion, input.expectedVersion()).set(SystemDataPolicyEntity::getChatAllowed, input.chatAllowed())
                        .set(SystemDataPolicyEntity::getEmbeddingAllowed, input.embeddingAllowed()).set(SystemDataPolicyEntity::getUpdatedAt, Instant.now().toString())
                        .set(SystemDataPolicyEntity::getApprovedGeneration, generation())
                        .set(SystemDataPolicyEntity::getVersion, input.expectedVersion() + 1));
                if (changed != 1) { throw IntegrationInputs.error(409, "DATA_POLICY_VERSION_CONFLICT"); }
            }
            return policy(system);
        });
    }
    /** 对话批准被撤销后拒绝工具取资料或再次外发旧资料。 */
    @Override public void requireChat(String systemId) {
        if (!policy(systemId).chatAllowed()) { throw IntegrationInputs.error(403, "DATA_EXTERNALIZATION_DENIED"); }
    }
    /** 不允许把未批准的向量错误伪装成混合检索成功。 */
    @Override public void requireEmbedding(String systemId) {
        if (!policy(systemId).embeddingAllowed()) { throw IntegrationInputs.error(403, "EMBEDDING_POLICY_DENIED"); }
    }
}
