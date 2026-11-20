package ai.neargo.shop.gateway.core.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 被配置关闭的一端：前缀下一律 404，请求到不了入口。
 *
 * <p>回 404 而不是 401 / 403：对外的说法是「这里没有这个接口」，与部署面没带这一端时的表现一致。
 */
public class GatewayEndClosedFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException {
        response.sendError(HttpServletResponse.SC_NOT_FOUND);
    }
}
