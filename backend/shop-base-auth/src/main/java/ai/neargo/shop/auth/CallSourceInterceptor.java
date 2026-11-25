package ai.neargo.shop.auth;

import ai.neargo.shop.common.RequestLogContext;
import ai.neargo.shop.spi.pay.CallSources;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

/**
 * 把这次 HTTP 请求绑成 {@link CallSources} 的来源（ADR-045 G9；开发计划 M2.1）：请求里任何地方调支付，
 * 来源都带着上游接口（路由模板）、端码、终端用户 IP、登录人与请求号。
 *
 * <p>挂在 MVC 拦截器而不是过滤器：到这里认证链已跑完（有登录人与端码），路由模板也已匹配出来。
 * IP 只经 {@link ClientMeta}（全仓唯一读代理头的地方）。
 */
public class CallSourceInterceptor implements AsyncHandlerInterceptor {

    private static final String SCOPE = CallSourceInterceptor.class.getName() + ".SCOPE";

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) {
        RequestMetaContext.Meta meta = RequestMetaContext.current();
        String requestId = RequestLogContext.requestId(req);
        req.setAttribute(SCOPE, CallSources.bind(CallSources.Origin.http(
                requestId, RequestLogContext.method(req) + " " + RequestLogContext.route(req, resp.getStatus()),
                meta == null || meta.clientType() == null ? CallSources.UNKNOWN : meta.clientType(),
                ClientMeta.clientIp(req),
                req.getHeader("User-Agent"),
                SecurityUtils.currentUserNoOrNull())));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse resp, Object handler, Exception ex) {
        closeScope(req);
    }

    @Override
    public void afterConcurrentHandlingStarted(HttpServletRequest req, HttpServletResponse resp, Object handler) {
        closeScope(req);
    }

    private static void closeScope(HttpServletRequest req) {
        if (req.getAttribute(SCOPE) instanceof CallSources.Scope scope) {
            scope.close();
            req.removeAttribute(SCOPE);
        }
    }
}
