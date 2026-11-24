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
 * 支付运营接口经网关透传（TDD-支付运营端经网关转发与权限下沉 §2.3）：{@code /ops/pay-ops/v2/**} → 支付服务 {@code /pay-ops/v2/**}。
 *
 * <p>是 {@link PrefixPassThrough} 在运营端的一个实例（TDD-支付三端经网关透传与权限归属 §3.1），挂在运营端链的授权过滤器之后
 * （见 {@link OpsGatewayEnd}）。网关在这里不判任何权限码，权限由支付服务拿令牌去统一认证接口换来后自己判（用户 2026-11-20 定）。
 *
 * <p>开关 {@code shop.gateway.ops.pay-ops-proxy.enabled}，默认关；关时这条过滤器不装，地址落到 MVC 回 404。
 * 下游地址 {@code shop.services.targets.PAY}。
 */
public final class PayOpsPassThrough {

    public static final String PREFIX = "/ops/pay-ops/v2";
    static final String SWITCH = "shop.gateway.ops.pay-ops-proxy.enabled";

    private final PrefixPassThrough passThrough;

    PayOpsPassThrough(PrefixPassThrough passThrough) {
        this.passThrough = passThrough;
    }

    /** 装进运营端链的过滤器；不是 Spring bean，免得被容器再全局注册一次 */
    public OncePerRequestFilter filter() {
        return passThrough.filter();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = SWITCH, havingValue = "true")
    static class PayOpsPassThroughConfiguration {

        @Bean
        PayOpsPassThrough payOpsPassThrough(ObjectProvider<ServiceLocator> locator) {
            return new PayOpsPassThrough(PrefixPassThrough.of(PREFIX, ServiceName.PAY, "/pay-ops/v2", SWITCH, locator));
        }
    }
}
