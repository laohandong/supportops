package io.supportops.integration.service.support;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 企业 MCP 传输同时校验真实回环对端和进程内随机密钥，Host 不能代替。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class InternalMcpAccessFilter extends OncePerRequestFilter {
    private final boolean enterprise;
    private final String transportKey;
    /** 启动时生成随机传输密钥，不持久化或记录。 */
    public InternalMcpAccessFilter(@Value("${supportops.enterprise.enabled:false}") boolean enterprise) {
        this.enterprise = enterprise;
        byte[] random = new byte[32]; new SecureRandom().nextBytes(random);
        transportKey = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }
    /** 只供进程内部 MCP 客户端使用，不提供 HTTP 获取入口。 */
    public String transportKey() { return transportKey; }
    /** 企业代理不得转发此路径，即使伪造回环 Host 也不能通过。 */
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (enterprise && ("/mcp".equals(request.getServletPath()) || request.getServletPath().startsWith("/mcp/"))) {
            String supplied = request.getHeader("X-SupportOps-Internal-Key");
            boolean local = Set.of("127.0.0.1", "0:0:0:0:0:0:0:1", "::1").contains(request.getRemoteAddr());
            if (!local || supplied == null || supplied.length() > 128 || !MessageDigest.isEqual(transportKey.getBytes(StandardCharsets.US_ASCII),
                    supplied.getBytes(StandardCharsets.US_ASCII))) { response.sendError(403); return; }
        }
        chain.doFilter(request, response);
    }
}
