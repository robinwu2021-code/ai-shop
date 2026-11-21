package ai.neargo.shop.gateway.core.integration;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 关掉的网关代理对象：它的前缀下一律 404，请求到不了入口（TDD-网关层与统一权限 §2.22）。
 *
 * <p>回 404 而不是 403：对外的说法是「这里没有这个接口」，与模块没装、一端被关时的表现一致。排在安全过滤器之前，
 * 所以登没登录都是 404。
 */
public class IntegrationGateFilter extends OncePerRequestFilter {

    private final IntegrationRegistry registry;

    public IntegrationGateFilter(IntegrationRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (registry.blocked(path)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        chain.doFilter(request, response);
    }
}
