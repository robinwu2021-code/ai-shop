package ai.neargo.shop.gateway.biz;

import ai.neargo.shop.gateway.core.proxy.PrefixPassThrough;
import ai.neargo.shop.svc.ServiceLocator;
import ai.neargo.shop.svc.ServiceName;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.List;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * B 端支付接口经网关透传（TDD-支付三端经网关透传与权限归属 §3.1）：{@code /biz/pay/v2/**} → 支付服务 {@code /biz/pay/v2/**}。
 *
 * <p>{@link PrefixPassThrough} 在 B 端的实例，挂在认证 {@code /biz/pay/v2/**} 的那条链的授权过滤器之后：
 * 开了 {@code shop.pay.biz-onboarding.enabled} 是支付进件专用链（{@code PayBizOnboardingConfiguration}，v2 上就是它），
 * 否则是 B 端主链（{@link BizGatewayEnd}）：走到这里的已是有效的商户会话；
 * 网关不判权限码，也不再改写商户号、不加来源委托头 —— 谁提供接口谁判权，near-pay 拿令牌去
 * {@code /internal/auth/v1/merchant-staff} 换本人在各支付商户下的码后自己判。
 *
 * <p>开关 {@code shop.gateway.biz.pay-proxy.enabled}，默认关；关时不装，{@code /biz/pay/v2/**} 仍由现有的转发入口
 * （{@code BizPayOnboardingController} 等，带店主来源委托）处理。打开前 near-pay 要先改成按统一认证接口判权（§5 阶段 2），
 * 否则店主会失去来源委托带来的全权。下游地址 {@code shop.services.targets.PAY}。
 */
public final class PayBizPassThrough {

    public static final String PREFIX = "/biz/pay/v2";
    static final String SWITCH = "shop.gateway.biz.pay-proxy.enabled";
    /** App 选中的支付商户与门店、链路号：near-pay 按它们定位商户（与原转发入口 {@code BizOnboardingForwarder} 放行的一致） */
    static final List<String> REQUEST_HEADERS = List.of("X-Merchant-No", "X-Store-No", "X-Trace-Id");

    private final PrefixPassThrough passThrough;

    PayBizPassThrough(PrefixPassThrough passThrough) {
        this.passThrough = passThrough;
    }

    /** 装进 B 端链的过滤器；不是 Spring bean，免得被容器再全局注册一次 */
    public OncePerRequestFilter filter() {
        return passThrough.filter();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = SWITCH, havingValue = "true")
    public static class PayBizPassThroughConfiguration {

        @Bean
        PayBizPassThrough payBizPassThrough(ObjectProvider<ServiceLocator> locator) {
            return new PayBizPassThrough(PrefixPassThrough.of(PREFIX, ServiceName.PAY, "/biz/pay/v2", SWITCH, locator,
                    REQUEST_HEADERS));
        }
    }
}
