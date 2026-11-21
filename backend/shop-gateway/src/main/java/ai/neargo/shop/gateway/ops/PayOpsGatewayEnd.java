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
import ai.neargo.shop.pay.api.guard.PortalFace;
import ai.neargo.shop.pay.api.guard.core.GuardFilters;
import jakarta.servlet.Filter;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 支付运营端（ADR-044 OPS 面；安全规范 §2.7）：与 {@code /ops/**} 同一套运营会话与 RBAC（权限码 {@code pay:*}），
 * 仅 ops 部署装配；接入层过滤器在令牌过滤器之后写请求记录。nginx 侧与运营前端同一份白名单（G1）。
 */
@ConditionalOnGatewayEnd(end = "ops", legacyProfiles = "ops")
public class PayOpsGatewayEnd extends AbstractGatewayEnd {

    private final TokenStore tokenStore;
    private final ObjectProvider<LiveIdentityResolver> identityResolver;
    private final ObjectProvider<PermChecker> perm;
    private final GuardFilters portalFilters;

    public PayOpsGatewayEnd(Environment environment, ObjectProvider<GatewayProperties> properties,
                            TokenStore tokenStore, ObjectProvider<LiveIdentityResolver> identityResolver,
                            GuardFilters portalFilters, ObjectProvider<PermChecker> perm) {
        super(environment, properties);
        this.tokenStore = tokenStore;
        this.identityResolver = identityResolver;
        this.portalFilters = portalFilters;
        this.perm = perm;
    }

    @Bean
    @Order(8)
    SecurityFilterChain payOpsChain(HttpSecurity http, SpringSecurityAdapter adapter) throws Exception {
        return adapter.install(http, spec());
    }

    @Override
    protected String name() {
        return "pay-ops";
    }

    /** 跟着运营端一起开关。 */
    @Override
    protected String switchName() {
        return "ops";
    }

    @Override
    protected Realm realm() {
        return Realm.OPERATOR;
    }

    @Override
    protected String pathPrefix() {
        return PortalFace.OPS.prefix();
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

    @Override
    protected List<Filter> contextFilters() {
        return List.of(portalFilters.forEntry(PortalFace.OPS));
    }
}
