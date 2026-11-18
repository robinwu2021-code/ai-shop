package ai.neargo.shop.gateway.core.proxy;

import ai.neargo.shop.gateway.core.resource.ApiResource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 判权通过之后，把请求交到该去的地方（TDD-网关层与统一权限 §2.14 的接缝三）：
 * 本进程入口，或某个微服务。以后换一种转发方式，只在这里加一个实现。
 */
public interface ServiceInvoker {

    boolean supports(ApiResource resource);

    void invoke(ApiResource resource, HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException;
}
