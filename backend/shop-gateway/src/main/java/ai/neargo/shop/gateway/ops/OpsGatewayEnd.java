package ai.neargo.shop.gateway.ops;

import ai.neargo.shop.auth.AbstractTokenAuthFilter;
import ai.neargo.shop.auth.LiveIdentityResolver;
import ai.neargo.shop.auth.OperatorTokenAuthFilter;
import ai.neargo.shop.auth.Realm;
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
import org.springframework.security.web.SecurityFilterChain;

/** 运营端出口（{@code /ops/**}）：全部需要登录 + RBAC。仅 ops 部署装配，且 ops 只在内网可达。 */
@ConditionalOnGatewayEnd(end = "ops", legacyProfiles = "ops")
public class OpsGatewayEnd extends AbstractGatewayEnd {

    private final TokenStore tokenStore;
    private final ObjectProvider<LiveIdentityResolver> identityResolver;

    public OpsGatewayEnd(Environment environment, ObjectProvider<GatewayProperties> properties,
                         TokenStore tokenStore, ObjectProvider<LiveIdentityResolver> identityResolver) {
        super(environment, properties);
        this.tokenStore = tokenStore;
        this.identityResolver = identityResolver;
    }

    @Bean
    @Order(3)
    SecurityFilterChain operatorChain(HttpSecurity http, SpringSecurityAdapter adapter) throws Exception {
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
