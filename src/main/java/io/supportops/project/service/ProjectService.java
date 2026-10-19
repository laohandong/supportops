package io.supportops.project.service;

import io.supportops.project.dto.GrantCommand;
import io.supportops.project.dto.ResourceCommand;
import io.supportops.project.vo.GrantView;
import io.supportops.project.vo.ResourceView;
import io.supportops.project.vo.TargetView;
import java.util.List;

/** 企业项目层级、用户授权和可信目标解析的业务边界。 */
public interface ProjectService {
    /** 管理员列出全部项目、系统和环境。 */
    List<ResourceView> resources();
    /** 按固定资源类型创建或条件更新。 */
    ResourceView save(String kind, ResourceCommand command);
    /** 设置项目成员资格或环境授权。 */
    GrantView grant(String kind, GrantCommand command);
    /** 返回用户当前可用且已启用连接的目标。 */
    List<TargetView> targets(String userId, boolean administrator);
    /** 解析可用目标，必要时重新核对用户成员及环境授权。 */
    TargetView resolve(String environmentId, String userId, boolean administrator);
    /** 捕获当前资源版本及人员授权关系的安全摘要，供不可变受理快照审阅。 */
    String authorizationRevision(TargetView target, String userId, boolean administrator);
    /** 历史记录按当前成员和环境授权读取，停用资源不会删除已保存事实。 */
    boolean historyAllowed(String environmentId, String userId, boolean administrator);
    /** 验证资料写入的项目、系统与环境关系，不读取 HTTP 身份。 */
    void validateScope(String projectId, String systemId, String environmentId);
    /** 校验资料所属系统及项目可用，不依赖当前HTTP身份。 */
    void validateSystem(String systemId);
    /** 激活通过验证的连接，使用旧版本条件更新环境指针。 */
    void activate(String environmentId, String revisionId, int expectedVersion);
}
