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
 *
 * <p><b>当前门店</b>由请求头 {@code X-Store-No} 指定，不传时用默认店。
 * 传了一个自己没权限的门店号：**直接不认，回落到默认店**而不是照它查 ——
 * 越权门店的数据一行都不能出去；而这里不抛错是因为它多半只是端上缓存了一个旧门店号
 * （店被停用了、授权被收回了），让整个 App 报错不如把他带回默认店。
 */
public class BizContextFilter extends OncePerRequestFilter {

    /** 端上带当前门店用这个头。放头里而不是每个接口加参数：它是**整个会话的上下文**，不是某个查询的条件 */
    public static final String STORE_HEADER = "X-Store-No";

    private final BizIdentityResolver resolver;

    public BizContextFilter(BizIdentityResolver resolver) {
        this.resolver = resolver;
    }

    /** 请求头指定的门店必须在我的权限集合里，否则按默认店处理。 */
    private BizContext withRequestedStore(BizContext ctx, HttpServletRequest req) {
        String requested = req.getHeader(STORE_HEADER);
        if (requested == null || requested.isBlank()) {
            return ctx;
        }
        return ctx.storeNos().contains(requested) ? ctx.withStore(requested) : ctx;
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
                    .ifPresent(u -> BizContext.set(withRequestedStore(resolver.resolve(u.userNo()), req)));
            chain.doFilter(req, resp);
        } finally {
            BizContext.clear();
        }
    }
}
