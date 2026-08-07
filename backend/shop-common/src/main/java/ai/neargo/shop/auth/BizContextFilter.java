package ai.neargo.shop.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 只作用于 {@code /biz/**}：把登录用户解析成经营侧作用域（{@link BizContext}）。
 *
 * <p>放在 {@link ConsumerTokenAuthFilter} 之后，因为它要用登录态。
 * 解析一次、缓存在 ThreadLocal，避免同一请求里每个 Service 各查一遍归属。
 */
public class BizContextFilter extends OncePerRequestFilter {

    private final BizIdentityResolver resolver;

    public BizContextFilter(BizIdentityResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        return !req.getRequestURI().startsWith("/biz/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        try {
            SecurityUtils.currentUser()
                    .filter(LoginUser::isConsumer)
                    .ifPresent(u -> BizContext.set(resolver.resolve(u.userNo())));
            chain.doFilter(req, resp);
        } finally {
            BizContext.clear();
        }
    }
}
