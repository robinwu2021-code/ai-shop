package ai.neargo.shop.pay.api.common;

import ai.neargo.shop.pay.api.auth.PortalPrincipal;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * controller 只能经参数拿到「谁在调」（{@link PortalPrincipal}），由过滤器认出、放进请求属性 ——
 * 不接受任何「我是谁」的入参（TDD-支付服务-I5接入层 F02）。
 */
@Configuration
public class PortalWebConfig implements WebMvcConfigurer {

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter p) {
                return PortalPrincipal.class.equals(p.getParameterType());
            }

            @Override
            public Object resolveArgument(MethodParameter p, ModelAndViewContainer mav, NativeWebRequest web,
                                          WebDataBinderFactory binder) {
                Object who = web.getAttribute(PortalRequests.PRINCIPAL, NativeWebRequest.SCOPE_REQUEST);
                if (who == null) {
                    throw new IllegalStateException("接入层过滤器没有认出调用方 —— 这个 controller 不在任何鉴权链上");
                }
                return who;
            }
        });
    }
}
