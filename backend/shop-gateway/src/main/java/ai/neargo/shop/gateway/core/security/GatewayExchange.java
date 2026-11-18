package ai.neargo.shop.gateway.core.security;

import ai.neargo.shop.gateway.core.resource.ApiResource;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;

/** 逐请求的网关上下文，放在请求属性里（不用线程变量，随请求对象回收）。目前只有命中的资源行。 */
public final class GatewayExchange {

    private static final String RESOURCE = GatewayExchange.class.getName() + ".resource";

    private GatewayExchange() {
    }

    public static void resource(HttpServletRequest request, ApiResource resource) {
        request.setAttribute(RESOURCE, resource);
    }

    public static Optional<ApiResource> resource(HttpServletRequest request) {
        return Optional.ofNullable((ApiResource) request.getAttribute(RESOURCE));
    }
}
