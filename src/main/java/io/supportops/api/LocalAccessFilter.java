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

/** 限制本地演示的浏览器访问来源，并提供页面资源安全策略。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LocalAccessFilter extends OncePerRequestFilter {
    private static final Set<String> HOSTS = Set.of("127.0.0.1", "localhost", "[::1]", "::1");

    /** 校验回环 Host、同源 Origin 和跨站标记，再按页面设置资源策略。 */
    @Override
    protected void doFilterInternal(
            HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        boolean allowed = HOSTS.contains(req.getServerName());
        String origin = req.getHeader("Origin");
        if (origin != null) {
            try {
                URI uri = URI.create(origin);
                int port =
                        uri.getPort() < 0
                                ? ("https".equals(uri.getScheme()) ? 443 : 80)
                                : uri.getPort();
                allowed &=
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
}
