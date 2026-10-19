package io.supportops.integration.service.support;

import io.supportops.integration.vo.ApplicationPrincipal;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** 机器请求的独立认证上下文，不能注入 CurrentUser。 */
public final class CurrentApplication {
    public static final String ATTRIBUTE = "supportops.applicationPrincipal";
    /** 上下文工具禁止实例化。 */
    private CurrentApplication() {}
    /** 只返回应用过滤器已验证的身份，缺失时拒绝请求。 */
    public static ApplicationPrincipal get() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                && attributes.getRequest().getAttribute(ATTRIBUTE) instanceof ApplicationPrincipal principal) { return principal; }
        throw IntegrationInputs.error(401, "APPLICATION_AUTHENTICATION_REQUIRED");
    }
}
