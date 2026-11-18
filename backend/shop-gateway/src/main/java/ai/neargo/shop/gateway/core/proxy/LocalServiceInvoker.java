package ai.neargo.shop.gateway.core.proxy;

import ai.neargo.shop.gateway.core.resource.ApiResource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/** 本进程入口：放给后面的过滤器与 Spring MVC。 */
public class LocalServiceInvoker extends AbstractServiceInvoker {

    @Override
    public boolean supports(ApiResource resource) {
        return !resource.remote();
    }

    @Override
    protected void doInvoke(ApiResource resource, HttpServletRequest request, HttpServletResponse response,
                            FilterChain chain) throws IOException, ServletException {
        chain.doFilter(request, response);
    }
}
