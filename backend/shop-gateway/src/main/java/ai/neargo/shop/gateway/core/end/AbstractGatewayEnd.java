package ai.neargo.shop.gateway.core.end;

import ai.neargo.shop.auth.AbstractTokenAuthFilter;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.gateway.core.config.GatewayEndProperties;
import ai.neargo.shop.gateway.core.config.GatewayEndSwitch;
import ai.neargo.shop.gateway.core.config.GatewayProperties;
import ai.neargo.shop.gateway.core.security.AccessDecider;
import jakarta.servlet.Filter;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;

/**
 * 一端出口的基类（TDD-网关层与统一权限 §2.21；ADR-060）。
 *
 * <p><b>流程写在这里，差别写在子类。</b>拆分前三端的认证链是同一个配置类里三段长得很像的代码，
 * 改一处（比如新增一种放行规则）要记得改三处，而漏掉的那一处不会报错。现在链怎么拼由 {@link #spec()} 固定，
 * 子类只回答「我这一端是什么」。
 *
 * <p>子类必须给的四样：端名、认哪个 {@link Realm}、地址前缀、令牌认证过滤器。
 * 有默认值的三样：公开地址（默认没有）、认证之后的端专属过滤器（默认没有）、其余地址的放行方式（默认要登录）。
 */
public abstract class AbstractGatewayEnd {

    private final Environment environment;
    private final ObjectProvider<GatewayProperties> properties;

    protected AbstractGatewayEnd(Environment environment, ObjectProvider<GatewayProperties> properties) {
        this.environment = environment;
        this.properties = properties;
    }

    /** 本端认证链的描述。流程固定：先看开关，关着就只给一条封住前缀的链，不建任何过滤器。 */
    public final GatewayEndSpec spec() {
        if (GatewayEndSwitch.resolve(environment, switchName(), legacyProfiles()) == GatewayEndSwitch.State.CLOSED) {
            return GatewayEndSpec.closed(name(), realm(), pathPrefix());
        }
        return new GatewayEndSpec(name(), realm(), pathPrefix(), publicPaths(), localDefault(),
                authenticator(), List.copyOf(contextFilters()), false, accessDecider(), precheck());
    }

    /** 配置里写了就用配置的，没写用本端的默认清单。 */
    private List<String> publicPaths() {
        GatewayProperties all = properties.getIfAvailable();
        GatewayEndProperties mine = all == null ? null : all.end(name());
        List<String> configured = mine == null ? null : mine.getPublicPaths();
        return configured == null ? List.copyOf(defaultPublicPaths()) : List.copyOf(configured);
    }

    /** 提前判权的档位取自开关所在的那一块配置，所以支付运营面跟着运营端走。 */
    private String precheck() {
        GatewayProperties all = properties.getIfAvailable();
        GatewayEndProperties mine = all == null ? null : all.end(switchName());
        return mine == null ? "off" : mine.getPrecheck();
    }

    // ---- 每一端必须回答的 ----

    /** 端名：mp / biz / ops / pay-ops。 */
    protected abstract String name();

    protected abstract Realm realm();

    /** 地址前缀，不带结尾的斜杠，如 {@code /biz}。 */
    protected abstract String pathPrefix();

    /**
     * 本端的令牌认证过滤器。<b>每次都新建，不要做成 Spring bean</b>：
     * 做成 bean 的过滤器会被 Servlet 容器在安全链之外再挂一遍。
     */
    protected abstract AbstractTokenAuthFilter authenticator();

    /** 开关没配时，本端跟着哪些部署面走。 */
    protected abstract String[] legacyProfiles();

    // ---- 有默认值，需要时才覆盖 ----

    /** 开关用的端名。支付运营面跟着运营端一起开关，所以它不等于 {@link #name()}。 */
    protected String switchName() {
        return name();
    }

    protected List<String> defaultPublicPaths() {
        return List.of();
    }

    /** 认证之后依次执行的端专属过滤器。同样每次新建、不做成 bean。 */
    protected List<Filter> contextFilters() {
        return List.of();
    }

    /** 提前判权的判定器。默认没有：该端不做提前判权，判权只靠方法上的注解。 */
    protected AccessDecider accessDecider() {
        return null;
    }

    protected Access localDefault() {
        return Access.LOGIN;
    }
}
