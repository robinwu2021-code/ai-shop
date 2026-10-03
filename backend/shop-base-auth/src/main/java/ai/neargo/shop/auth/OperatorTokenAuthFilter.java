package ai.neargo.shop.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.ArrayList;
import java.util.List;

/**
 * 运营端认证过滤器（{@code /ops/**}）：只认 {@code realm=OPERATOR}。
 *
 * <p>与另外两端的区别有两处：授予 {@code ROLE_*} 权威（供 {@code @PreAuthorize} 用），
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
public class OperatorTokenAuthFilter extends AbstractTokenAuthFilter {

    private final ObjectProvider<LiveIdentityResolver> identityResolver;

    public OperatorTokenAuthFilter(TokenStore tokenStore,
                                   ObjectProvider<LiveIdentityResolver> identityResolver) {
        super(tokenStore, Realm.OPERATOR);
        this.identityResolver = identityResolver;
    }

    /** 粗判操作端：目前只分「运营后台网页」与「未知」，够用——真要精确区分再细分 UA。 */
    @Override
    protected String clientCode(HttpServletRequest req) {
        String ua = req.getHeader("User-Agent");
        return ua == null || ua.isBlank() ? "UNKNOWN" : "WEB_OPS";
    }

    @Override
    protected Authenticated authenticate(LoginUser sessionUser) {
        /*
         * **角色与数据域现算，不用会话里那份。**
         *
         * 会话里的是登录那一刻的快照：给某人换个角色、收窄他的数据域，
         * 不重建会话就没有任何机制能让它生效 —— 不是「滞后到下次登录」，
         * 是他不主动重登就永远是旧的，而他没有理由去重登。
         *
         * 解析不出来（未装配、库抖）就回落会话那份 —— 见类注释。
         */
        LiveIdentityResolver.Identity live =
                identityResolver.getIfAvailable(() -> LiveIdentityResolver.NONE)
                        .resolve(sessionUser.userNo());
        List<String> roles = live != null && live.roles() != null
                ? live.roles() : sessionUser.roles();
        var scope = live != null && live.scope() != null
                ? live.scope() : sessionUser.dataScope();

        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_OPERATOR"));
        roles.forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));

        /*
         * **判权读的 LoginUser 也要换成现算的这份**。只换 authorities 的话，
         * `@PreAuthorize("@perm.can(...)")` 走的仍是 `user.roles()` 的旧快照 ——
         * 于是「菜单按新角色画、判权按旧角色算」，两边各自说得通。
         */
        return new Authenticated(sessionUser.withRolesAndScope(roles, scope), authorities);
    }
}
