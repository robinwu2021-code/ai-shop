package ai.neargo.shop.gateway.core.end;

import ai.neargo.shop.auth.AbstractTokenAuthFilter;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.gateway.core.security.AccessDecider;
import jakarta.servlet.Filter;
import java.util.List;

/**
 * 一端认证链的全部差别，由 {@link AbstractGatewayEnd#spec()} 产出，交给安全框架适配器去装。
 *
 * <p>它不引用任何安全框架的类型：端只描述「我是谁、认什么、放行什么」，怎么装进框架是适配器的事。
 *
 * @param name           端名：mp / biz / ops / pay-ops
 * @param pathPrefix     地址前缀，如 {@code /biz}
 * @param publicPaths    不用登录就能访问的地址
 * @param localDefault   其余地址默认的放行方式
 * @param authenticator  令牌认证过滤器；{@code closed} 时为 null
 * @param contextFilters 认证之后依次执行的端专属过滤器
 * @param closed         端被配置关闭而入口仍在：只装一条回 404 的链
 * @param decider        提前判权的判定器；没有则不做提前判权
 * @param precheck       提前判权的档位：off / observe / enforce
 */
public record GatewayEndSpec(String name, Realm realm, String pathPrefix, List<String> publicPaths,
                             Access localDefault, AbstractTokenAuthFilter authenticator,
                             List<Filter> contextFilters, boolean closed,
                             AccessDecider decider, String precheck) {

    public static GatewayEndSpec closed(String name, Realm realm, String pathPrefix) {
        return new GatewayEndSpec(name, realm, pathPrefix, List.of(), Access.LOGIN, null, List.of(), true,
                null, "off");
    }

    /** 这一端有判定器，且档位不是关。 */
    public boolean prechecks() {
        return decider != null && ("observe".equalsIgnoreCase(precheck) || "enforce".equalsIgnoreCase(precheck));
    }
}
