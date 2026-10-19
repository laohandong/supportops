package io.supportops.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import java.util.Arrays;
import java.util.stream.Collectors;

/** 限制本地演示的浏览器访问来源，并提供页面资源安全策略。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LocalAccessFilter extends OncePerRequestFilter {
    private static final Set<String> HOSTS = Set.of("127.0.0.1", "localhost", "[::1]", "::1");
    private final boolean enterprise;
    private final URI publicOrigin;
    private final Set<String> trustedProxies;
    /** 保留本地过滤器的历史无参验证入口。 */
    public LocalAccessFilter() { this(new StandardEnvironment()); }
    /** 企业仅接受部署者登记的 HTTPS 来源和精确信任的代理地址。 */
    @Autowired public LocalAccessFilter(Environment environment) {
        enterprise = environment.getProperty("supportops.enterprise.enabled", Boolean.class, false);
        publicOrigin = enterprise ? URI.create(environment.getRequiredProperty("supportops.enterprise.public-origin")) : null;
        trustedProxies = Arrays.stream(environment.getProperty("supportops.enterprise.trusted-proxies", "").split(","))
                .map(String::strip).filter(value -> !value.isEmpty()).collect(Collectors.toUnmodifiableSet());
        if (enterprise && (!"https".equals(publicOrigin.getScheme()) || publicOrigin.getHost() == null
                || publicOrigin.getRawUserInfo() != null || publicOrigin.getRawQuery() != null || publicOrigin.getRawFragment() != null
                || (!publicOrigin.getRawPath().isEmpty() && !"/".equals(publicOrigin.getRawPath())))) {
            throw new IllegalArgumentException("ENTERPRISE_HTTPS_ORIGIN_REQUIRED");
        }
    }

    /** 校验回环 Host、同源 Origin 和跨站标记，再按页面设置资源策略。 */
    @Override
    protected void doFilterInternal(
            HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        boolean allowed = enterprise ? enterpriseRequest(req) : HOSTS.contains(req.getServerName());
        String origin = req.getHeader("Origin");
        if (origin != null) {
            try {
                URI uri = URI.create(origin);
                int port =
                        uri.getPort() < 0
                                ? ("https".equals(uri.getScheme()) ? 443 : 80)
                                : uri.getPort();
                allowed &= enterprise ? publicOrigin.getHost().equalsIgnoreCase(uri.getHost())
                        && port == (publicOrigin.getPort() < 0 ? 443 : publicOrigin.getPort()) && "https".equals(uri.getScheme())
                        && uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null
                        && uri.getRawPath().isEmpty() :
                        HOSTS.contains(uri.getHost())
                                && port == req.getServerPort()
                                && uri.getScheme().equals(req.getScheme());
            } catch (Exception e) {
                allowed = false;
            }
        }
        if ("cross-site".equals(req.getHeader("Sec-Fetch-Site"))) {
            allowed = false;
        }
        if (!allowed) {
            res.sendError(403);
            return;
        }
        res.setHeader("X-Content-Type-Options", "nosniff");
        // Swagger UI uses generated inline styles; keep scripts local and the workbench's policy
        // unchanged.
        boolean swaggerPage =
                req.getRequestURI()
                        .substring(req.getContextPath().length())
                        .startsWith("/swagger-ui/");
        res.setHeader(
                "Content-Security-Policy",
                "default-src 'self'; script-src 'self'; style-src 'self'"
                        + (swaggerPage ? " 'unsafe-inline'" : "")
                        + "; img-src 'self' data:; frame-ancestors 'none'; base-uri 'self';"
                        + " form-action 'self'");
        chain.doFilter(req, res);
    }
    /** 代理头只有来自精确登记的 TCP 对端才可使用，Host 仍须匹配企业域名。 */
    private boolean enterpriseRequest(HttpServletRequest request) {
        boolean internalMcp = "/mcp".equals(request.getServletPath()) || request.getServletPath().startsWith("/mcp/");
        if (internalMcp) {
            // 这里只允许回环传输进入下一层；随机凭据必须由内部 MCP 过滤器进一步验证。
            return Set.of("127.0.0.1", "0:0:0:0:0:0:0:1", "::1").contains(request.getRemoteAddr())
                    && HOSTS.contains(request.getServerName()) && request.getHeader("Origin") == null
                    && request.getHeader("X-SupportOps-Internal-Key") != null;
        }
        if (!publicOrigin.getHost().equalsIgnoreCase(request.getServerName())) { return false; }
        int port = publicOrigin.getPort() < 0 ? 443 : publicOrigin.getPort();
        if (request.isSecure()) { return request.getServerPort() == port; }
        String authority = publicOrigin.getRawAuthority();
        return trustedProxies.contains(request.getRemoteAddr()) && "https".equals(request.getHeader("X-Forwarded-Proto"))
                && authority.equalsIgnoreCase(request.getHeader("X-Forwarded-Host"));
    }
}
