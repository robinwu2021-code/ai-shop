package ai.neargo.shop.gateway.ops;

import ai.neargo.shop.gateway.core.proxy.PassThroughInvoker;
import ai.neargo.shop.gateway.core.resource.ApiResource;
import ai.neargo.shop.svc.ServiceLocator;
import ai.neargo.shop.svc.ServiceName;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 支付运营接口经网关透传（TDD-支付运营端经网关转发与权限下沉 §2.3）：{@code /ops/pay-ops/v2/**} → 支付服务 {@code /pay-ops/v2/**}。
 *
 * <p>挂在运营端链的授权过滤器<b>之后</b>（见 {@link OpsGatewayEnd}）：走到这里的请求已经是有效的运营会话；
 * 网关在这里不判任何权限码，权限由支付服务拿令牌去统一认证接口换来后自己判（用户 2026-11-20 定）。
 * 不经 Spring MVC：MVC 会先解析 multipart、吃掉原始请求体。该前缀不在资源索引里，提前判权对它不生效。
 *
 * <p>开关 {@code shop.gateway.ops.pay-ops-proxy.enabled}，默认关；关时这条过滤器不装，地址落到 MVC 回 404。
 * 下游地址 {@code shop.services.targets.PAY}。
 */
public final class PayOpsPassThrough {

    public static final String PREFIX = "/ops/pay-ops/v2";
    static final ApiResource ROUTE = PassThroughInvoker.route(PREFIX + "/**", ServiceName.PAY, "/pay-ops/v2/**");

    private final PassThroughInvoker invoker;

    PayOpsPassThrough(PassThroughInvoker invoker) {
        this.invoker = invoker;
    }

    /** 装进运营端链的过滤器；不是 Spring bean，免得被容器再全局注册一次 */
    public OncePerRequestFilter filter() {
        return new OncePerRequestFilter() {
            @Override
            protected boolean shouldNotFilter(HttpServletRequest request) {
                String path = request.getRequestURI().substring(request.getContextPath().length());
                return !(path.equals(PREFIX) || path.startsWith(PREFIX + "/"));
            }

            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                invoker.invoke(ROUTE, request, response, chain);
            }
        };
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "shop.gateway.ops.pay-ops-proxy.enabled", havingValue = "true")
    static class PayOpsPassThroughConfiguration {

        @Bean
        PayOpsPassThrough payOpsPassThrough(ObjectProvider<ServiceLocator> locator) {
            ServiceLocator found = locator.getIfAvailable();
            if (found == null || found.baseUrlOf(ServiceName.PAY).isEmpty()) {
                throw new IllegalStateException("开了 shop.gateway.ops.pay-ops-proxy.enabled 却没有配 shop.services.targets.PAY");
            }
            return new PayOpsPassThrough(new PassThroughInvoker(found));
        }
    }
}
