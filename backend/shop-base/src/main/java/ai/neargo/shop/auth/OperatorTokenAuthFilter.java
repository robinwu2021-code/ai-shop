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
 * <p>权限变更后的在线会话处理留到 S7：届时比对 {@code permStamp} 并按需重建会话
 * —— 现在没有角色管理功能，先不做，省得留一段没人验证的代码。
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
        try {
            chain.doFilter(req, resp);
        } finally {
            if (scopeSet) {
                DataScopeContext.clear();
            }
        }
    }
}
