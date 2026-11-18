package ai.neargo.shop.gateway.biz;

import ai.neargo.shop.auth.AbstractTokenAuthFilter;
import ai.neargo.shop.auth.BizContextFilter;
import ai.neargo.shop.auth.BizIdentityResolver;
import ai.neargo.shop.auth.MerchantTokenAuthFilter;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.config.ClientVersionGateFilter;
import ai.neargo.shop.gateway.core.config.ConditionalOnGatewayEnd;
import ai.neargo.shop.gateway.core.config.GatewayProperties;
import ai.neargo.shop.gateway.core.end.AbstractGatewayEnd;
import ai.neargo.shop.gateway.core.security.AccessDecider;
import ai.neargo.shop.gateway.core.security.SpringSecurityAdapter;
import ai.neargo.shop.pay.api.guard.PortalFace;
import ai.neargo.shop.pay.api.guard.core.GuardFilters;
import ai.neargo.shop.platform.AppVersionService;
import jakarta.servlet.Filter;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.ObjectMapper;

/** B 端出口（{@code /biz/**}）：要登录；能做什么由门店上下文与权限码判。 */
@ConditionalOnGatewayEnd(end = "biz", legacyProfiles = "api")
public class BizGatewayEnd extends AbstractGatewayEnd {

    private final TokenStore tokenStore;
    private final ObjectProvider<BizIdentityResolver> resolver;
    private final ObjectProvider<BizContextFilter.Admission> admission;
    private final GuardFilters portalFilters;
    private final ObjectProvider<AppVersionService> appVersions;
    private final ObjectProvider<ObjectMapper> json;

    public BizGatewayEnd(Environment environment, ObjectProvider<GatewayProperties> properties,
                         TokenStore tokenStore, ObjectProvider<BizIdentityResolver> resolver,
                         ObjectProvider<BizContextFilter.Admission> admission, GuardFilters portalFilters,
                         ObjectProvider<AppVersionService> appVersions, ObjectProvider<ObjectMapper> json) {
        super(environment, properties);
        this.tokenStore = tokenStore;
        this.resolver = resolver;
        this.admission = admission;
        this.portalFilters = portalFilters;
        this.appVersions = appVersions;
        this.json = json;
    }

    @Bean
    @Order(1)
    SecurityFilterChain merchantChain(HttpSecurity http, SpringSecurityAdapter adapter) throws Exception {
        return adapter.install(http, spec());
    }

    @Override
    protected String name() {
        return "biz";
    }

    @Override
    protected Realm realm() {
        return Realm.MERCHANT;
    }

    @Override
    protected String pathPrefix() {
        return "/biz";
    }

    @Override
    protected String[] legacyProfiles() {
        return new String[] {"api"};
    }

    /*
     * **只认 btk_（A7）。** 此前这条链与 /mp/** 合用一条，挂的是
     * ConsumerTokenAuthFilter —— 商家拿的是 C 端的 ctk_，于是
     * LoginUser.userNo 这一个字段里 C 端塞 usr_account.user_no、
     * B 端塞 mch_account.mch_account_no，靠号段恰好不撞。
     * 分链之后跨端令牌在第一道就被拒，不是靠约定。
     */
    @Override
    protected AbstractTokenAuthFilter authenticator() {
        return new MerchantTokenAuthFilter(tokenStore);
    }

    /*
     * 登录前的三个动作都必须放行。**发验证码也在其中** ——
     * 它此前漏在白名单外，于是商家点「获取验证码」拿到 401：
     * 要先登录才能拿到登录用的验证码，谁也进不来。
     * 而这条路径只有真的从登录页点一次才会走到。
     *
     * staff-login 是**员工独立登录**：他可能根本没有 C 端账号，
     * 要求先登录才能登录是个死循环。
     */
    @Override
    protected List<String> defaultPublicPaths() {
        return List.of("/biz/auth/login", "/biz/auth/staff-login",
                "/biz/auth/otp/send", "/biz/auth/email-otp/send",
                "/biz/auth/identities", "/biz/auth/identities/enter",
                "/biz/auth/captcha/challenge", "/biz/auth/captcha/verify");
    }

    /** 按当前门店的角色提前判权；档位由 {@code shop.gateway.biz.precheck} 定，默认关。 */
    @Override
    protected AccessDecider accessDecider() {
        return new BizAccessDecider();
    }

    @Override
    protected List<Filter> contextFilters() {
        List<Filter> filters = new ArrayList<>();
        /*
         * 旧版本写入被拒（TDD-商户App问题回报与版本 V7）：认证之后、选店之前判 —— 旧包的写请求
         * 不该先撞上门店准入（70006）再被告知升级。只认请求头，不带头的请求一律放行。
         * 用 ObjectProvider 与上面两个一样：只装了安全链的切片测试里没有版本策略，那里退回不拦。
         */
        AppVersionService versions = appVersions.getIfAvailable();
        ObjectMapper mapper = json.getIfAvailable();
        if (versions != null && mapper != null) {
            filters.add(new ClientVersionGateFilter(versions, mapper));
        }
        // BizContextFilter 必须在认证之后：它要用登录态去解析经营侧作用域
        filters.add(new BizContextFilter(resolver.getIfAvailable(() -> BizIdentityResolver.NONE),
                admission.getIfAvailable()));
        // 商家端支付（/biz/pay/**，ADR-044 BIZ 面）：复用上面认好的商家会话，接入层只做请求记录与「必须是商家令牌」。
        // 其它 /biz 路径它不碰（过滤器按入口前缀自判）
        filters.add(portalFilters.forEntry(PortalFace.BIZ));
        return filters;
    }
}
