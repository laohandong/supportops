package io.supportops.api;

import io.supportops.user.CurrentUser;
import io.supportops.user.controller.UserController;
import io.supportops.user.service.UserService;
import io.supportops.user.vo.UserView;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/** 登录认证与默认拒绝的后台权限边界；本地来源过滤先于本过滤器执行。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class UserAccessFilter extends OncePerRequestFilter {
    private final UserService users;
    private long loginWindow;
    private int loginAttempts;
    /** 注入用户查询服务，每次请求重新验证角色。 */
    public UserAccessFilter(UserService users) {
        this.users = users;
    }
    /** 业务 API 必须登录；普通用户仅允许明确列出的客户工作区接口。 */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String path = request.getServletPath();
        boolean docs = path.startsWith("/v3/api-docs") || path.startsWith("/swagger-ui");
        if (!path.startsWith("/api/") && !docs) {
            chain.doFilter(request, response);
            return;
        }
        response.setHeader("Cache-Control", "no-store");
        // 自定义头无法由跨站 HTML 表单提交；结合来源检查和 SameSite Cookie 阻止 CSRF。
        if ("POST".equals(request.getMethod()) && !"1".equals(request.getHeader("X-SupportOps-Request"))) {
            reject(response, 403, "REQUEST_HEADER_REQUIRED");
            return;
        }
        if (path.equals("/api/auth/setup") || path.equals("/api/auth/login")) {
            if ("POST".equals(request.getMethod()) && !allowLoginAttempt()) {
                response.setHeader("Retry-After", "60");
                reject(response, 429, "LOGIN_RATE_LIMITED");
                return;
            }
            chain.doFilter(request, response);
            return;
        }
        HttpSession session = request.getSession(false);
        String userId = session == null ? null : (String) session.getAttribute(UserController.SESSION_USER);
        UserView user = userId == null ? null : users.find(userId);
        if (user == null) {
            reject(response, 401, "AUTHENTICATION_REQUIRED");
            return;
        }
        if (!user.isAdmin() && (docs || !customerEndpoint(path, request.getMethod()))) {
            reject(response, 403, "ADMIN_REQUIRED");
            return;
        }
        request.setAttribute(CurrentUser.ATTRIBUTE, user);
        chain.doFilter(request, response);
    }
    /** 只允许诊断及当前账号接口；新增后台接口默认仅管理员可用。 */
    private boolean customerEndpoint(String path, String method) {
        if ("GET".equals(method)) {
            return path.equals("/api/auth/me") || path.equals("/api/status") || path.equals("/api/runs")
                    || path.matches("/api/runs/[0-9a-fA-F-]{36}(/events|/stream)?");
        }
        return "POST".equals(method) && (path.equals("/api/auth/logout") || path.equals("/api/runs")
                || path.matches("/api/runs/[0-9a-fA-F-]{36}/(cancel|archive)"));
    }
    /** 本地单实例每分钟最多受理 30 次认证尝试，限制密码派生成本。 */
    private synchronized boolean allowLoginAttempt() {
        long window = System.currentTimeMillis() / 60_000;
        if (window != loginWindow) {
            loginWindow = window;
            loginAttempts = 0;
        }
        return ++loginAttempts <= 30;
    }
    /** 返回稳定 JSON 错误，不泄漏账号信息或凭据。 */
    private void reject(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + code + "\"}");
    }
}
