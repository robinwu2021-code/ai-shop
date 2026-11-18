package ai.neargo.shop.gateway.core.security;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.gateway.core.resource.ApiResource;
import ai.neargo.shop.gateway.core.resource.ResourceIndexes;
import ai.neargo.shop.gateway.core.resource.ResourceStatusCache;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 提前判权：在读请求体、进入口方法之前，凭「地址 + 登录人的权限码」判一次
 * （TDD-网关层与统一权限 §2.13；开发计划 M4）。
 *
 * <p>排在令牌认证与端专属过滤器之后。两种情况不在这里处理、原样往后放：
 * 没登录的（认证链会回 401）；索引里查不到的地址（后面照旧处理，三端索引建全之前不在这里回 404）。
 *
 * <p>两档：
 * <ul>
 *   <li>观察：算出「会拒绝」只记一条日志，照常放行 —— 上线初期用它核对与方法注解的结论是否一致；</li>
 *   <li>拦截：被拒的请求到此为止，响应的包体与方法注解拒绝时相同（见 {@link GatewayErrorWriter}）。</li>
 * </ul>
 * 方法上的 {@code @PreAuthorize} 不受影响，仍会再判一次。
 */
public class GatewayPrecheckFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(GatewayPrecheckFilter.class);

    private final ResourceIndexes indexes;
    private final List<String> pathPrefixes;
    private final AccessDecider decider;
    private final boolean enforce;
    private final GatewayErrorWriter errors;
    private final String endCode;
    private final ResourceStatusCache statuses;

    public GatewayPrecheckFilter(ResourceIndexes indexes, List<String> pathPrefixes, AccessDecider decider,
                                 boolean enforce, GatewayErrorWriter errors,
                                 String endCode, ResourceStatusCache statuses) {
        this.indexes = indexes;
        this.pathPrefixes = pathPrefixes;
        this.decider = decider;
        this.enforce = enforce;
        this.errors = errors;
        this.endCode = endCode;
        this.statuses = statuses;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        Optional<ApiResource> resource = indexes.of(pathPrefixes).locate(request.getMethod(), path);
        resource.ifPresent(hit -> GatewayExchange.resource(request, hit));
        // 被停用的链接：观察与拦截两档都生效，回 404 —— 停用是运营明确做的动作，不属于「先观察」的范围
        if (resource.isPresent() && statuses != null && statuses.isDisabled(endCode, resource.get())) {
            log.warn("链接已停用 {} {}", request.getMethod(), resource.get().pattern());
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        LoginUser user = SecurityUtils.currentUser().orElse(null);
        if (user == null || resource.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        Decision decision = decider.decide(user, resource.get());
        if (decision.permitted()) {
            chain.doFilter(request, response);
            return;
        }
        String line = request.getMethod() + " " + resource.get().pattern();
        if (!enforce) {
            log.warn("提前判权（观察）会拒绝 {} · 缺 {} · {}", line, decision.missing(), resource.get().handler());
            chain.doFilter(request, response);
            return;
        }
        log.warn("提前判权拒绝 {} · 缺 {}", line, decision.missing());
        errors.forbidden(response);
    }
}
