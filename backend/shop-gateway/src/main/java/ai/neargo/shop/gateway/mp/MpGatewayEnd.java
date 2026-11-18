package ai.neargo.shop.gateway.mp;

import ai.neargo.shop.auth.AbstractTokenAuthFilter;
import ai.neargo.shop.auth.ConsumerTokenAuthFilter;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.gateway.core.config.ConditionalOnGatewayEnd;
import ai.neargo.shop.gateway.core.config.GatewayProperties;
import ai.neargo.shop.gateway.core.end.AbstractGatewayEnd;
import ai.neargo.shop.gateway.core.end.Access;
import ai.neargo.shop.gateway.core.security.SpringSecurityAdapter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * C 端（{@code /mp/**}）：只认 {@code ctk_}。
 *
 * <p><b>整条链放行到业务层</b>：游客能逛商品、看门店主页，需要登录的端点
 * 自己调 {@code SecurityUtils.currentUserNo()} 抛 401。
 * 这是刻意的 —— 门店主页未登录不该 401，所以才有分开的
 * {@code currentUserNoOrNull()}。哪些端点要登录由 {@code MpEndpointAuthTest} 逐条钉住。
 */
@ConditionalOnGatewayEnd(end = "mp", legacyProfiles = "api")
public class MpGatewayEnd extends AbstractGatewayEnd {

    private final TokenStore tokenStore;

    public MpGatewayEnd(Environment environment, ObjectProvider<GatewayProperties> properties, TokenStore tokenStore) {
        super(environment, properties);
        this.tokenStore = tokenStore;
    }

    @Bean
    @Order(2)
    SecurityFilterChain consumerChain(HttpSecurity http, SpringSecurityAdapter adapter) throws Exception {
        return adapter.install(http, spec());
    }

    @Override
    protected String name() {
        return "mp";
    }

    @Override
    protected Realm realm() {
        return Realm.CONSUMER;
    }

    @Override
    protected String pathPrefix() {
        return "/mp";
    }

    @Override
    protected String[] legacyProfiles() {
        return new String[] {"api"};
    }

    @Override
    protected AbstractTokenAuthFilter authenticator() {
        return new ConsumerTokenAuthFilter(tokenStore);
    }

    /** 整条链放行到业务层，理由见类注释。 */
    @Override
    protected Access localDefault() {
        return Access.PUBLIC;
    }
}
