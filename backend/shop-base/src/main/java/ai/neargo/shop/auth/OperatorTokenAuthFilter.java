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
import java.util.ArrayList;
import java.util.List;

/**
 * 运营端认证过滤器（{@code /ops/**}）：只认 {@code realm=OPERATOR}。
 *
 * <p>与 C 端的区别有两处：授予 {@code ROLE_*} 权威（供 {@code @PreAuthorize} 用），
 * 以及数据域来自员工授权（商家/社区/自提点），不是 SELF。
 *
 * <p>权限变更**不必重建会话**：判权由 {@code LivePermResolver} 现算，改完配置下一个
 * 请求就是新权限（2026-08-12）。停用账号仍走 {@code revokeUser} 直接踢下线 ——
 * 那是「这个人不该再进来」，与「他的权限变了」是两件事。
 */
public class OperatorTokenAuthFilter extends OncePerRequestFilter {

    private final TokenStore tokenStore;

    public OperatorTokenAuthFilter(TokenStore tokenStore) {
        this.tokenStore = tokenStore;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        boolean scopeSet = false;
        String token = ConsumerTokenAuthFilter.bearer(req);
        if (token != null) {
            TokenStore.SessionData d = tokenStore.get(token).orElse(null);
            if (d != null && d.user().realm() == Realm.OPERATOR) {
                List<SimpleGrantedAuthority> authorities = new ArrayList<>();
                authorities.add(new SimpleGrantedAuthority("ROLE_OPERATOR"));
                d.user().roles().forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(d.user(), null, authorities));
                DataScopeContext.set(d.user().dataScope());
                scopeSet = true;
            }
        }
        /*
         * 审计要记 IP/操作端——这是 web 层唯一能碰 HttpServletRequest 的地方之一，
         * platform 域只读 RequestMetaContext 这个 ThreadLocal（见其类注释）。
         */
        RequestMetaContext.set(new RequestMetaContext.Meta(clientIp(req), clientType(req)));
        try {
            chain.doFilter(req, resp);
        } finally {
            RequestMetaContext.clear();
            if (scopeSet) {
                DataScopeContext.clear();
            }
        }
    }

    private static String clientIp(HttpServletRequest req) {
        String forwarded = req.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }

    /** 粗判操作端：目前只分「运营后台网页」与「未知」，够用——真要精确区分再细分 UA。 */
    private static String clientType(HttpServletRequest req) {
        String ua = req.getHeader("User-Agent");
        return ua == null || ua.isBlank() ? "UNKNOWN" : "WEB_OPS";
    }
}
