package ai.neargo.shop.auth;

import ai.neargo.common.data.scope.DataScopeContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * C 端 / B 端认证过滤器（{@code /mp/**} 与 {@code /biz/**}）：Bearer → 会话，只认 {@code realm=CONSUMER}。
 *
 * <p>顺带把数据域塞进 {@code DataScopeContext}（C 端为 SELF），使 SQL 层获得防 IDOR 的兜底：
 * 就算某个 Service 忘了判属主，SQL 也带着 {@code user_no = 我} 出去。
 *
 * <p>{@code /biz/**} 的经营侧作用域由 {@link BizContextFilter} 在本过滤器<b>之后</b>解析
 * —— 它依赖这里放好的登录态。
 */
public class ConsumerTokenAuthFilter extends OncePerRequestFilter {

    private final TokenStore tokenStore;

    public ConsumerTokenAuthFilter(TokenStore tokenStore) {
        this.tokenStore = tokenStore;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        /*
         * IP 在这条链上也要设。
         *
         * **它此前只在 ops 链上设**，于是 /mp 与 /biz 的领域代码拿不到 IP ——
         * 发码限流的「同 IP 每小时 N 次」这一道就是废的（拿不到 IP 时它放行）。
         * 而换着手机号刷码的机器人**只会撞这一道**：前两道按号计数，对它无效。
         */
        RequestMetaContext.set(ClientMeta.of(req, req.getRequestURI().startsWith("/biz") ? "APP_BIZ" : "APP_C"));

        boolean scopeSet = false;
        String token = bearer(req);
        if (token != null) {
            TokenStore.SessionData d = tokenStore.get(token).orElse(null);
            if (d != null && d.user().realm() == Realm.CONSUMER) {
                var auth = new UsernamePasswordAuthenticationToken(
                        d.user(), null, List.of(new SimpleGrantedAuthority("ROLE_CONSUMER")));
                SecurityContextHolder.getContext().setAuthentication(auth);
                DataScopeContext.set(d.user().dataScope());
                scopeSet = true;
            }
        }
        try {
            chain.doFilter(req, resp);
        } finally {
            RequestMetaContext.clear();
            if (scopeSet) {
                DataScopeContext.clear();
            }
        }
    }

    static String bearer(HttpServletRequest req) {
        String header = req.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        String token = header.substring(7).trim();
        return token.isEmpty() ? null : token;
    }
}
