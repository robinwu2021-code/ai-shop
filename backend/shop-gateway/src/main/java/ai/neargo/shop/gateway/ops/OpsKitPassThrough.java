package ai.neargo.shop.gateway.ops;

import ai.neargo.shop.gateway.core.proxy.PrefixPassThrough;
import ai.neargo.shop.svc.ServiceLocator;
import ai.neargo.shop.svc.ServiceName;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 审批页面经网关透传到 OpsKit（TDD-OpsKit审批对接 §1.2，契约 §1）：{@code /ops/opskit/v1/**} → OpsKit {@code /api/opskit/v1/**}。
 *
 * <p>与 {@link PayOpsPassThrough} 同形：挂在运营端链的授权过滤器之后，网关只认会话、不判权限码；
 * 运营的会话令牌原样转给 OpsKit，OpsKit 回 ai-shop 内省端点换身份后自己判。浏览器不直连 OpsKit（它只在内网）。
 *
 * <p>开关 {@code shop.gateway.ops.opskit-proxy.enabled}，默认关；关时地址落到 MVC 回 404。
 * 下游地址 {@code shop.services.targets.OPSKIT}（如 {@code http://127.0.0.1:8090}），或 Nacos 映射 {@code OPSKIT → opskit}。
 */
public final class OpsKitPassThrough {

    public static final String PREFIX = "/ops/opskit/v1";
    static final String SWITCH = "shop.gateway.ops.opskit-proxy.enabled";

    private final PrefixPassThrough passThrough;

    OpsKitPassThrough(PrefixPassThrough passThrough) {
        this.passThrough = passThrough;
    }

    /** 装进运营端链的过滤器；不是 Spring bean，免得被容器再全局注册一次 */
    public OncePerRequestFilter filter() {
        return passThrough.filter();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = SWITCH, havingValue = "true")
    static class OpsKitPassThroughConfiguration {

        @Bean
        OpsKitPassThrough opsKitPassThrough(ObjectProvider<ServiceLocator> locator) {
            return new OpsKitPassThrough(PrefixPassThrough.of(PREFIX, ServiceName.OPSKIT, "/api/opskit/v1", SWITCH, locator));
        }
    }
}
