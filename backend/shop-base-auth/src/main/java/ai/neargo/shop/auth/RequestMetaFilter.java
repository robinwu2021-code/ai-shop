package ai.neargo.shop.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 给不经令牌认证的入口（渠道回调）也设上 {@link RequestMetaContext}：领域代码只读这个 ThreadLocal、不碰 servlet
 * （{@code ArchitectureTest#domainsMustNotTouchWebRuntime}），而可信 IP 只有 {@link ClientMeta} 一处来源（安全规范 §4.5）。
 * 令牌认证链自己会设（{@code AbstractTokenAuthFilter}），这里只补它够不着的入口。
 */
public final class RequestMetaFilter extends OncePerRequestFilter {

    private final String clientType;

    public RequestMetaFilter(String clientType) {
        this.clientType = clientType;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        RequestMetaContext.set(ClientMeta.of(req, clientType));
        try {
            chain.doFilter(req, resp);
        } finally {
            RequestMetaContext.clear();
        }
    }
}
