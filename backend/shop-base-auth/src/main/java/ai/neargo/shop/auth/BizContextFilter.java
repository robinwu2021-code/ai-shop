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
 *
 * <p><b>门店号要在解析<i>之前</i>就交给解析器</b>（{@link BizIdentityResolver#resolve(String, String)}）。
 * 这里原来是先 {@code resolve(userNo)} 再把门店套上去 —— 单主体时等价，
 * 而一个人有两张营业执照时，那个顺序会让他进 B 主体的店却仍以 A 主体的身份查库：
 * 权限、商品、订单全是 A 的，页面照常打开。所以解析要「按店反查主体」，
 * 而门店号只有这一层拿得到。
 *
 * <p>套门店这一步<b>仍然保留</b>，它守的是另一件事：同一个主体下，店员只被授权到部分门店。
 * 解析器认执照，这里认门店，两层各管各的。
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
            /*
             * **判据是「这是不是 B 端会话」，不是「主体是哪张表的号」。**
             *
             * A7 之前这里写的是 isConsumer()：那时店主和店员拿的都是 ctk_
             * （realm=CONSUMER，只是 subjectKind 一个 USR 一个 MCH），所以成立。
             * A7 把两者都改发 btk_ 之后这个判据对谁都不成立 —— 认证过了、
             * BizContext 却是空的，表现为**所有 /biz/** 一律 403**：
             * 不是 401，看不出是登录问题；而 resolve 从没被调用过。
             *
             * 用 realm 判而不是干脆去掉：这条链虽然只挂在 /biz/**，
             * 但「C 端会话不得建立经营作用域」是这一层要守的东西，
             * 留着它，将来谁把过滤器挂到别处也不会静默放行。
             * 两种主体号 resolve 都认（见 BizIdentityResolverImpl 的 user_no OR mch_account_no）。
             */
            SecurityUtils.currentUser()
                    .filter(u -> u.realm() == Realm.MERCHANT)
                    .ifPresent(u -> BizContext.set(withRequestedStore(
                            resolver.resolve(u.userNo(), req.getHeader(STORE_HEADER)), req)));
            chain.doFilter(req, resp);
        } finally {
            BizContext.clear();
        }
    }
}
