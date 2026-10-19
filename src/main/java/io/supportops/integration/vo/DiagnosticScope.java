package io.supportops.integration.vo;

import io.supportops.project.vo.TargetView;
import io.supportops.config.ModelExecutionSnapshot;
import java.util.Set;

/** 受理时捕获的可信权限快照；地址、秘密及模型凭据不进入快照。 */
public record DiagnosticScope(TargetView target, String actorType, String actorId, String keyId,
        boolean administratorAtAdmission, Set<String> modelData, boolean documentChatAllowed,
        boolean documentEmbeddingAllowed, int dataPolicyVersion, String deploymentId,
        String authorizationRevision, ModelExecutionSnapshot.Description modelConfiguration) {
    /** 兼容早期隔离构造，真实受理补充部署、授权版本与安全模型描述。 */
    public DiagnosticScope(TargetView target, String actorType, String actorId, String keyId,
            boolean administratorAtAdmission, Set<String> modelData, boolean documentChatAllowed,
            boolean documentEmbeddingAllowed, int dataPolicyVersion) {
        this(target, actorType, actorId, keyId, administratorAtAdmission, modelData, documentChatAllowed,
                documentEmbeddingAllowed, dataPolicyVersion, null, null, null);
    }
    /** 旧隔离验证构造器采用示例资料策略，企业受理必须显式捕获策略。 */
    public DiagnosticScope(TargetView target, String actorType, String actorId, String keyId,
            boolean administratorAtAdmission, Set<String> modelData) {
        this(target, actorType, actorId, keyId, administratorAtAdmission, modelData, true, true, 0);
    }
}
