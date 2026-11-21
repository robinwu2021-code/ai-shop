package ai.neargo.shop.gateway.ops;

import ai.neargo.shop.auth.AbstractTokenAuthFilter;
import ai.neargo.shop.auth.LiveIdentityResolver;
import ai.neargo.shop.auth.OperatorTokenAuthFilter;
import ai.neargo.shop.auth.PermChecker;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.gateway.core.security.AccessDecider;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.gateway.core.config.ConditionalOnGatewayEnd;
import ai.neargo.shop.gateway.core.config.GatewayProperties;
import ai.neargo.shop.gateway.core.end.AbstractGatewayEnd;
import ai.neargo.shop.gateway.core.security.SpringSecurityAdapter;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.SecurityFilterChain;

/** 运营端出口（{@code /ops/**}）：全部需要登录 + RBAC。仅 ops 部署装配，且 ops 只在内网可达。 */
@ConditionalOnGatewayEnd(end = "ops", legacyProfiles = "ops")
public class OpsGatewayEnd extends AbstractGatewayEnd {

    private final TokenStore tokenStore;
    private final ObjectProvider<LiveIdentityResolver> identityResolver;
    private final ObjectProvider<PermChecker> perm;

    public OpsGatewayEnd(Environment environment, ObjectProvider<GatewayProperties> properties,
                         TokenStore tokenStore, ObjectProvider<LiveIdentityResolver> identityResolver,
                         ObjectProvider<PermChecker> perm) {
        super(environment, properties);
        this.tokenStore = tokenStore;
        this.identityResolver = identityResolver;
        this.perm = perm;
    }

    @Bean
    @Order(3)
    SecurityFilterChain operatorChain(HttpSecurity http, SpringSecurityAdapter adapter,
                                      ObjectProvider<PayOpsPassThrough> payOps) throws Exception {
        // 支付运营接口透传：放在授权之后，走到它的都已登录；开关关时没有这个 bean（TDD-支付运营端经网关转发与权限下沉 §2.3）
        PayOpsPassThrough passThrough = payOps.getIfAvailable();
        if (passThrough != null) {
            http.addFilterAfter(passThrough.filter(), AuthorizationFilter.class);
        }
        return adapter.install(http, spec());
    }

    @Override
    protected String name() {
        return "ops";
    }

    @Override
    protected Realm realm() {
        return Realm.OPERATOR;
    }

    @Override
    protected String pathPrefix() {
        return "/ops";
    }

    @Override
    protected String[] legacyProfiles() {
        return new String[] {"ops"};
    }

    /** 权限检查器取不到（只装了认证链的切片测试）时不做提前判权。 */
    @Override
    protected AccessDecider accessDecider() {
        PermChecker checker = perm.getIfAvailable();
        return checker == null ? null : new OpsAccessDecider(checker);
    }

    @Override
    protected AbstractTokenAuthFilter authenticator() {
        return new OperatorTokenAuthFilter(tokenStore, identityResolver);
    }

    // 忘记密码的两条同样免鉴权：忘了密码自然登不进来。
    // 安全性靠邮件里那个一次性令牌，以及「账号不存在也返回成功」
    // 退出也放行：过期令牌点退出要得到成功；接口只吊销请求头里那一个令牌
    @Override
    protected List<String> defaultPublicPaths() {
        return List.of("/ops/auth/login", "/ops/auth/login/verify", "/ops/auth/forgot",
                "/ops/auth/reset", "/ops/auth/logout");
    }
}
