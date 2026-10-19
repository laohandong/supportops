package io.supportops.integration.service;

import io.supportops.integration.dto.AccessKeyCommand;
import io.supportops.integration.dto.ApplicationCommand;
import io.supportops.integration.dto.RevokeKeyCommand;
import io.supportops.integration.vo.AccessKeyIssuance;
import io.supportops.integration.vo.AccessKeyView;
import io.supportops.integration.vo.ApplicationPrincipal;
import io.supportops.integration.vo.ApplicationView;
import java.util.List;

/** 应用授权与密钥认证，独立于浏览器用户会话。 */
public interface ApplicationService {
    /** 管理员读取应用公开信息。 */
    List<ApplicationView> list();
    /** 条件更新应用及同项目环境授权。 */
    ApplicationView save(ApplicationCommand command);
    /** 签发一次性密钥，同键重放不返回明文。 */
    AccessKeyIssuance issue(AccessKeyCommand command);
    /** 读取应用密钥状态。 */
    List<AccessKeyView> keys(String applicationId);
    /** 紧急撤销访问密钥。 */
    AccessKeyView revoke(RevokeKeyCommand command);
    /** 验证 Bearer 并返回独立应用身份。 */
    ApplicationPrincipal authenticate(String bearer);
    /** 每次读取、工具调用与外发重新核对身份、权限及环境授权。 */
    void authorize(ApplicationPrincipal actor, String permission, String environmentId);
    /** 捕获已认证应用的授权版本摘要，不包含访问密钥摘要。 */
    String authorizationRevision(ApplicationPrincipal actor);
    /** 按应用限制提交或读取速率。 */
    void rate(ApplicationPrincipal actor, boolean submission);
    /** 按资源释放边界受理应用活动名额。 */
    void acquire(ApplicationPrincipal actor);
    /** 任务资源退出后归还活动名额。 */
    void release(ApplicationPrincipal actor);
}
