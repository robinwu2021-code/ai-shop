package ai.neargo.shop.auth;

import ai.neargo.shop.spi.pay.CallSources;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * 把这次 HTTP 请求绑成 {@link CallSources} 的来源（ADR-045 G9；开发计划 M2.1）：请求里任何地方调支付，
 * 来源都带着上游接口（路由模板）、端码、终端用户 IP、登录人与请求号。
 *
 * <p>挂在 MVC 拦截器而不是过滤器：到这里认证链已跑完（有登录人与端码），路由模板也已匹配出来。
 * IP 只经 {@link ClientMeta}（全仓唯一读代理头的地方）。
 */
public class CallSourceInterceptor implements HandlerInterceptor {

    private static final String SCOPE = CallSourceInterceptor.class.getName() + ".SCOPE";

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) {
        Object pattern = req.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String path = pattern instanceof String p ? p : req.getRequestURI().substring(req.getContextPath().length());
        RequestMetaContext.Meta meta = RequestMetaContext.current();
        String rid = req.getHeader("X-Request-Id");
        req.setAttribute(SCOPE, CallSources.bind(CallSources.Origin.http(
                rid != null && rid.matches("[A-Za-z0-9._-]{8,64}") ? rid : null,
                req.getMethod() + " " + path,
                meta == null || meta.clientType() == null ? CallSources.UNKNOWN : meta.clientType(),
                ClientMeta.clientIp(req),
                req.getHeader("User-Agent"),
                SecurityUtils.currentUserNoOrNull())));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse resp, Object handler, Exception ex) {
        if (req.getAttribute(SCOPE) instanceof CallSources.Scope scope) {
            scope.close();
            req.removeAttribute(SCOPE);
        }
    }
}
