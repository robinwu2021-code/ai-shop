package ai.neargo.shop.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

/**
 * C 端认证过滤器（{@code /mp/**}）：只认 {@code realm=CONSUMER}。
 *
 * <p>数据域是 SELF，使 SQL 层获得防 IDOR 的兜底：
 * 就算某个 Service 忘了判属主，SQL 也带着 {@code user_no = 我} 出去。
 *
 * <p><b>它曾经同时管着 {@code /biz/**}</b>（B 端与 C 端共用 {@code ctk_} 令牌池），
 * 靠请求路径区分审计里的操作端。分池之后 B 端有了自己的
 * {@link MerchantTokenAuthFilter}，这里只剩 C 端。
 */
public class ConsumerTokenAuthFilter extends AbstractTokenAuthFilter {

    /**
     * @deprecated 已上移到 {@link AbstractTokenAuthFilter#TOKEN_EXPIRED_ATTR}。
     *         保留别名是因为三处调用方引的是这个名字，一次性改完不如让它们各自迁移；
     *         两个常量指向同一个字符串，行为完全一致。
     */
    @Deprecated
    public static final String TOKEN_EXPIRED_ATTR = AbstractTokenAuthFilter.TOKEN_EXPIRED_ATTR;

    public ConsumerTokenAuthFilter(TokenStore tokenStore) {
        super(tokenStore, Realm.CONSUMER);
    }

    /**
     * ⚠️ **暂时仍按路径判，不能写成常量 {@code APP_C}。**
     *
     * <p>今天 {@code /biz/**} 与 {@code /mp/**} 在**同一条 SecurityFilterChain** 上
     * （B 端还在用 {@code ctk_} 令牌），所以这个过滤器仍然会看到 B 端的请求。
     * 写成常量的话，B 端的操作端会全部被记成 {@code APP_C} ——
     * **审计数据悄悄变了，而不会有任何报错**。
     *
     * <p>等 B 端改发 {@code btk_}、{@code /biz/**} 切到
     * {@link MerchantTokenAuthFilter} 那条链之后，这里就可以收成常量了。
     * 在那之前，这个三元表达式是它自己存在的理由。
     */
    @Override
    protected String clientCode(HttpServletRequest req) {
        return req.getRequestURI().startsWith("/biz") ? "APP_BIZ" : "APP_C";
    }

    @Override
    protected Authenticated authenticate(LoginUser sessionUser) {
        // C 端没有 RBAC，身份原样用；属主鉴权由 SELF 数据域在 SQL 层兜底
        return new Authenticated(sessionUser,
                List.of(new SimpleGrantedAuthority("ROLE_CONSUMER")));
    }
}
