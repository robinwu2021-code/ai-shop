package ai.neargo.shop.pay.api.guard.web;

import ai.neargo.shop.pay.access.PayCaller;
import ai.neargo.shop.pay.acquiring.port.InternalCallers;
import ai.neargo.shop.pay.api.guard.core.PortalPrincipal;
import ai.neargo.shop.pay.api.guard.core.PortalRequests;
import ai.neargo.shop.pay.api.guard.open.service.AppAuthService;
import ai.neargo.shop.pay.api.guard.terminal.TerminalAuthService;
import ai.neargo.shop.pay.openapp.entity.PayApp;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * controller 经参数拿 {@link PayCaller}（ADR-045 §2-1；开发计划 M1.5）：守卫认出的 {@link PortalPrincipal} 在这里换成支付域的调用方。
 * <ul>
 *   <li>开放 API：参数上的 {@link CallerGrant} 给出授权动作 —— 启用 → 授权门店（scope 守卫已判，M3.3）；</li>
 *   <li>运营端：运营操作员（平台身份、不按门店圈），权限码判在端点的 {@code @perm.can}。</li>
 * </ul>
 * 商家端不经这里：它的调用方要按请求里的门店圈，门店要先过 {@code @PreAuthorize} 的门店校验，而参数解析在判权之前。
 * 其余入口（收银台访客等）的调用方由领域服务自己按令牌换出，在这里拿是写错了，直接失败。
 *
 * <p>参数解析按声明顺序：开放 API 端点把 {@code PayCaller} 放在请求体之后，请求体不合法仍先回 400，与原先一致。
 */
public class CallerArgumentResolver implements HandlerMethodArgumentResolver {

    private final ObjectProvider<AppAuthService> apps;
    private final InternalCallers callers;
    private final ObjectProvider<TerminalAuthService> terminals;

    public CallerArgumentResolver(ObjectProvider<AppAuthService> apps, InternalCallers callers,
                                  ObjectProvider<TerminalAuthService> terminals) {
        this.apps = apps;
        this.callers = callers;
        this.terminals = terminals;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return PayCaller.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav, NativeWebRequest web,
                                  WebDataBinderFactory binder) {
        Object attr = web.getAttribute(PortalRequests.PRINCIPAL, NativeWebRequest.SCOPE_REQUEST);
        if (!(attr instanceof PortalPrincipal who)) {
            throw new IllegalStateException("接入守卫没有认出调用方 —— " + parameter.getMethod() + " 不在任何守卫的入口上");
        }
        return switch (who.face()) {
            case OPEN -> open(who, parameter);
            case OPS -> callers.opsOperator(who.subjectId());
            // 终端：门店圈到绑定的那一家；收银员取自 X-Operator（M6.2）
            case TERMINAL -> terminals.getObject().callerFor(who.subjectId(),
                    web.getHeader("X-Operator"));
            default -> throw new IllegalStateException("入口 " + who.face() + " 不经参数给 PayCaller：" + parameter.getMethod());
        };
    }

    private PayCaller open(PortalPrincipal who, MethodParameter parameter) {
        CallerGrant grant = parameter.getParameterAnnotation(CallerGrant.class);
        if (grant == null) {
            throw new IllegalStateException("开放 API 的 PayCaller 参数要写 @CallerGrant(grant)：" + parameter.getMethod());
        }
        AppAuthService service = apps.getObject();
        PayApp app = service.requireActive(who.subjectId());
        return service.callerFor(app, grant.grant());
    }
}
