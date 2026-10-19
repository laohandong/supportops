package io.supportops.integration.service.support;

import io.supportops.integration.service.ApplicationService;
import io.supportops.integration.vo.ApplicationPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

/** 开放 API 必须使用独立 Bearer，Cookie 或后台管理员身份不能替代。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class ApplicationAccessFilter extends OncePerRequestFilter {
    private final ApplicationService applications;
    /** 注入应用认证和限流服务。 */
    public ApplicationAccessFilter(ApplicationService applications) { this.applications = applications; }
    /** 精确限定版本化机器入口，认证失败不给业务链建立人员身份。 */
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!request.getServletPath().startsWith("/open-api/")) { chain.doFilter(request, response); return; }
        response.setHeader("Cache-Control", "no-store");
        try {
            String header = request.getHeader("Authorization");
            if (header == null || !header.startsWith("Bearer ") || header.length() > 200) {
                throw IntegrationInputs.error(401, "APPLICATION_AUTHENTICATION_REQUIRED");
            }
            ApplicationPrincipal principal = applications.authenticate(header.substring(7));
            response.setHeader("X-SupportOps-Application-Id", principal.applicationId());
            boolean submission = "POST".equals(request.getMethod()) && "/open-api/v1/diagnoses".equals(request.getServletPath());
            applications.rate(principal, submission);
            request.setAttribute(CurrentApplication.ATTRIBUTE, principal);
            chain.doFilter(request, response);
        } catch (ResponseStatusException exception) {
            if (response.isCommitted()) { throw exception; }
            response.setStatus(exception.getStatusCode().value()); response.setContentType("application/json;charset=UTF-8");
            if (exception.getStatusCode().value() == 429) { response.setHeader("Retry-After", "60"); }
            response.getWriter().write("{\"error\":\"" + exception.getReason() + "\"}");
        }
    }
}
