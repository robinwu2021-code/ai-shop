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
 * <p><b>权限变更一律不必重建会话</b>：会话里只留「他是谁」（staffNo），
 * 角色与数据域每请求由 {@link LiveIdentityResolver} 现算，权限码再由
 * {@code LivePermResolver} 按角色现算。改角色、改数据域、改角色的功能点 ——
 * 下一个请求就是新的，而且<b>不打断任何人</b>。
 *
 * <p>会话只在两件事上被撤销，它们都不是「权限变了」：
 * <b>停用账号</b>（这个人不该再进来）与<b>改密码</b>（怀疑泄露），
 * 外加运营手动点的那个紧急撤回按钮。
 *
 * <p>解析不出来时<b>回落会话里的旧身份</b>，而不是拒绝 ——
 * 宁可多用一会儿旧的，也不要因为解析器没装上而全员失权。
 */
public class OperatorTokenAuthFilter extends OncePerRequestFilter {

    private final TokenStore tokenStore;
    private final org.springframework.beans.factory.ObjectProvider<LiveIdentityResolver> identityResolver;

    public OperatorTokenAuthFilter(
            TokenStore tokenStore,
            org.springframework.beans.factory.ObjectProvider<LiveIdentityResolver> identityResolver) {
        this.tokenStore = tokenStore;
        this.identityResolver = identityResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        boolean scopeSet = false;
        String token = ConsumerTokenAuthFilter.bearer(req);
        if (token != null) {
            TokenStore.SessionData d = tokenStore.get(token).orElse(null);
            // 带了令牌却查不到会话 = 已过期/被吊销。与「没带令牌」分开，理由见 ApiAuthEntryPoint
            if (d == null) {
                req.setAttribute(ConsumerTokenAuthFilter.TOKEN_EXPIRED_ATTR, Boolean.TRUE);
            }
            if (d != null && d.user().realm() == Realm.OPERATOR) {
                /*
                 * **角色与数据域现算，不用会话里那份。**
                 *
                 * 会话里的是登录那一刻的快照：给某人换个角色、收窄他的数据域，
                 * 不重建会话就没有任何机制能让它生效 —— 不是「滞后到下次登录」，
                 * 是他不主动重登就永远是旧的，而他没有理由去重登。
                 * 那三个写接口（setStaffRole/Roles/Scope）此前只能靠踢会话解决，
                 * 把一次调权变成一次打断。
                 *
                 * 解析不出来（未装配、库抖）就回落会话那份 —— 见类注释。
                 */
                LoginUser user = d.user();
                LiveIdentityResolver.Identity live =
                        identityResolver.getIfAvailable(() -> LiveIdentityResolver.NONE)
                                .resolve(user.userNo());
                List<String> roles = live != null && live.roles() != null
                        ? live.roles() : user.roles();
                var scope = live != null && live.scope() != null
                        ? live.scope() : user.dataScope();

                List<SimpleGrantedAuthority> authorities = new ArrayList<>();
                authorities.add(new SimpleGrantedAuthority("ROLE_OPERATOR"));
                roles.forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));
                /*
                 * **判权读的 LoginUser 也要换成现算的这份**。只换 authorities 的话，
                 * `@PreAuthorize("@perm.can(...)")` 走的仍是 `user.roles()` 的旧快照 ——
                 * 于是「菜单按新角色画、判权按旧角色算」，两边各自说得通。
                 */
                LoginUser effective = user.withRolesAndScope(roles, scope);
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(effective, null, authorities));
                DataScopeContext.set(scope);
                scopeSet = true;
            }
        }
        /*
         * 审计要记 IP/操作端——这是 web 层唯一能碰 HttpServletRequest 的地方之一，
         * platform 域只读 RequestMetaContext 这个 ThreadLocal（见其类注释）。
         */
        RequestMetaContext.set(ClientMeta.of(req, clientType(req)));
        try {
            chain.doFilter(req, resp);
        } finally {
            RequestMetaContext.clear();
            if (scopeSet) {
                DataScopeContext.clear();
            }
        }
    }

    /** 粗判操作端：目前只分「运营后台网页」与「未知」，够用——真要精确区分再细分 UA。 */
    private static String clientType(HttpServletRequest req) {
        String ua = req.getHeader("User-Agent");
        return ua == null || ua.isBlank() ? "UNKNOWN" : "WEB_OPS";
    }
}
