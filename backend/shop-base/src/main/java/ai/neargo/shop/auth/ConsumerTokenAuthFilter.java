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
            /*
             * **带了令牌却查不到会话 = 已过期/被吊销**，与「没带令牌」不是一回事。
             *
             * 两者此前都落成同一个空响应体的 401，端上分不出来 —— 而 B 端联调抓到的
             * 原缺陷正是把过期说成「没权限」：一个让人重新登录，一个让人去找老板要权限。
             *
             * 打在 request 属性上而不是当场抛：认证失败要由 entry point 统一收口，
             * 在过滤器里直接写响应会绕开那一层，两处各写一份格式迟早分叉。
             */
            if (d == null) {
                req.setAttribute(TOKEN_EXPIRED_ATTR, Boolean.TRUE);
            }
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

    /** 「带了令牌但会话没了」的标记。{@code ApiAuthEntryPoint} 据此选错误码 */
    public static final String TOKEN_EXPIRED_ATTR = "shop.auth.tokenExpired";

    static String bearer(HttpServletRequest req) {
        String header = req.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        String token = header.substring(7).trim();
        return token.isEmpty() ? null : token;
    }
}
