package ai.neargo.shop.gateway.core.security;

import ai.neargo.shop.auth.ApiAuthEntryPoint;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.gateway.core.end.Access;
import ai.neargo.shop.gateway.core.proxy.ServiceInvoker;
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
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 网关的定位、提前判权与分发（TDD-网关层与统一权限 §2.8、§2.13；开发计划 M4、D2）。
 *
 * <p>排在令牌认证与端专属过滤器之后，按「方法 + 地址」在资源索引里定位，然后分两路：
 *
 * <p><b>本进程入口</b>：按提前判权的档位处理。关：直接放行；观察：算出「会拒绝」只记日志、照常放行；
 * 拦截：被拒的请求到此为止。没登录的不在这里处理（认证链会回 401），方法上的 {@code @PreAuthorize} 仍会再判一次。
 *
 * <p><b>微服务链接</b>（只在这一端打开了转发时才会出现在索引里）：<b>不管档位是什么都在这里判完</b> ——
 * 它们没有方法注解可以兜底。没登录回 401，没权限回「没有操作权限」，通过了才转发。
 *
 * <p>索引里查不到的地址原样往后放，由后面照旧处理。被停用的链接回 404。
 */
public class GatewayPrecheckFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(GatewayPrecheckFilter.class);

    private final ResourceIndexes indexes;
    private final List<String> pathPrefixes;
    private final AccessDecider decider;
    private final String precheck;
    private final GatewayErrorWriter errors;
    private final String endCode;
    private final ResourceStatusCache statuses;
    private final List<ServiceInvoker> invokers;

    public GatewayPrecheckFilter(ResourceIndexes indexes, List<String> pathPrefixes, AccessDecider decider,
                                 String precheck, GatewayErrorWriter errors, String endCode,
                                 ResourceStatusCache statuses, List<ServiceInvoker> invokers) {
        this.indexes = indexes;
        this.pathPrefixes = pathPrefixes;
        this.decider = decider;
        this.precheck = precheck == null ? "off" : precheck.toLowerCase();
        this.errors = errors;
        this.endCode = endCode;
        this.statuses = statuses;
        this.invokers = invokers;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        Optional<ApiResource> located = indexes.of(pathPrefixes).locate(request.getMethod(), path);
        if (located.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        ApiResource resource = located.get();
        GatewayExchange.resource(request, resource);
        // 被停用的链接：不分档位都生效，回 404 —— 停用是运营明确做的动作，不属于「先观察」的范围
        if (statuses != null && statuses.isDisabled(endCode, resource)) {
            log.warn("链接已停用 {} {}", request.getMethod(), resource.pattern());
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        LoginUser user = SecurityUtils.currentUser().orElse(null);
        String line = request.getMethod() + " " + resource.pattern();

        if (resource.remote()) {
            if (GatewayExchange.localOnly(request)) {
                ApiAuthEntryPoint.writeError(request, response, 403, ErrorCode.FORBIDDEN);
                return;
            }
            if (user == null && resource.access() != Access.PUBLIC) {
                new ApiAuthEntryPoint().commence(request, response,
                        new InsufficientAuthenticationException("微服务链接需要登录"));
                return;
            }
            Decision decision = decider.decide(user, resource);
            if (!decision.permitted()) {
                log.warn("判权拒绝（微服务链接）{} · 缺 {}", line, decision.missing());
                errors.write(request, response, decider.denial(decision));
                return;
            }
            invoke(resource, request, response, chain);
            return;
        }

        // C 端的认证链整条放行，要不要登录本来由业务逐个入口判。库里把一条入口配成了「要登录」时，
        // 由网关在这里拦未登录的请求；另外两端的链自己会拦未登录的，轮不到这里
        if (user == null && "mp".equals(endCode) && !"off".equals(precheck)
                && (resource.access() == Access.LOGIN || resource.access() == Access.POINT)) {
            if ("enforce".equals(precheck)) {
                log.warn("提前判权拒绝 {} · 未登录", line);
                new ApiAuthEntryPoint().commence(request, response,
                        new InsufficientAuthenticationException("这条入口要登录"));
                return;
            }
            log.warn("提前判权（观察）会要求登录 {} · {}", line, resource.handler());
        }
        if (user != null && !"off".equals(precheck)) {
            Decision decision = decider.decide(user, resource);
            if (!decision.permitted()) {
                if ("enforce".equals(precheck)) {
                    log.warn("提前判权拒绝 {} · 缺 {}", line, decision.missing());
                    errors.write(request, response, decider.denial(decision));
                    return;
                }
                log.warn("提前判权（观察）会拒绝 {} · 缺 {} · {}", line, decision.missing(), resource.handler());
            }
        }
        invoke(resource, request, response, chain);
    }

    private void invoke(ApiResource resource, HttpServletRequest request, HttpServletResponse response,
                        FilterChain chain) throws ServletException, IOException {
        for (ServiceInvoker invoker : invokers) {
            if (invoker.supports(resource)) {
                invoker.invoke(resource, request, response, chain);
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
