package ai.neargo.shop.gateway.core.proxy;

import ai.neargo.shop.auth.ApiAuthEntryPoint;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.gateway.core.resource.ApiResource;
import ai.neargo.shop.gateway.core.security.GatewayExchange;
import ai.neargo.shop.svc.ServiceLocator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 一条整前缀透传（TDD-支付三端经网关透传与权限归属 §3.1）：对外前缀 → 下游服务的目标前缀，网关只认证、不判权限码。
 *
 * <p>每端一个实例（运营端 {@code /ops/pay-ops/v2}、B 端 {@code /biz/pay/v2}），由各端的出口挂在授权过滤器<b>之后</b>：
 * 走到这里的请求已经是该端的有效会话，别的端的令牌在认证那一步就被拒。不经 Spring MVC —— MVC 会先解析 multipart、
 * 吃掉原始请求体。透传的前缀不在资源索引里，提前判权对它不生效。只转 {@link PassThroughInvoker} 白名单里的请求头（加上这一端追加的），
 * 客户端带来的 {@code X-Internal-*} 一律不转。
 */
public final class PrefixPassThrough {

    private final String prefix;
    private final ApiResource route;
    private final PassThroughInvoker invoker;

    /**
     * @param prefix  对外前缀，不带结尾斜杠，如 {@code /biz/pay/v2}
     * @param service 下游服务名，如 {@code PAY}
     * @param target  下游前缀，不带结尾斜杠，如 {@code /biz/pay/v2}
     */
    public PrefixPassThrough(String prefix, String service, String target, PassThroughInvoker invoker) {
        this.prefix = prefix;
        this.route = PassThroughInvoker.route(prefix + "/**", service, target + "/**");
        this.invoker = invoker;
    }

    /** 开关打开时装配：服务地址没配就起不来，免得上线后每个请求都 502。 */
    public static PrefixPassThrough of(String prefix, String service, String target, String switchName,
                                       ObjectProvider<ServiceLocator> locator) {
        return of(prefix, service, target, switchName, locator, List.of());
    }

    /** 同上，另带这一端下游要的请求头（见 {@link PassThroughInvoker#PassThroughInvoker(ServiceLocator, List)}） */
    public static PrefixPassThrough of(String prefix, String service, String target, String switchName,
                                       ObjectProvider<ServiceLocator> locator, List<String> extraRequestHeaders) {
        ServiceLocator found = locator.getIfAvailable();
        if (found == null || found.baseUrlOf(service).isEmpty()) {
            throw new IllegalStateException("开了 " + switchName + " 却没有配 shop.services.targets." + service);
        }
        return new PrefixPassThrough(prefix, service, target, new PassThroughInvoker(found, extraRequestHeaders));
    }

    public String prefix() {
        return prefix;
    }

    public ApiResource route() {
        return route;
    }

    /** 装进端链的过滤器；不是 Spring bean，免得被容器再全局注册一次 */
    public OncePerRequestFilter filter() {
        return new OncePerRequestFilter() {
            @Override
            protected boolean shouldNotFilter(HttpServletRequest request) {
                String path = request.getRequestURI().substring(request.getContextPath().length());
                return !(path.equals(prefix) || path.startsWith(prefix + "/"));
            }

            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                if (GatewayExchange.localOnly(request)) {
                    ApiAuthEntryPoint.writeError(request, response, 403, ErrorCode.FORBIDDEN);
                    return;
                }
                invoker.invoke(route, request, response, chain);
            }
        };
    }
}
