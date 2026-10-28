package ai.neargo.shop.pay.api.guard.internal;

import ai.neargo.shop.pay.api.guard.ConditionalOnGuardEntry;
import ai.neargo.shop.pay.api.guard.PortalFace;
import ai.neargo.shop.pay.api.guard.core.GuardFilter;
import ai.neargo.shop.pay.api.guard.core.GuardFilters;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 内部入口的管线挂到 servlet 容器上（独立形态 {@code pay-svc} 没有 Spring Security 链，过滤器直接注册）。
 * 管线自己按前缀只管 {@code /internal/pay/v1}（{@link GuardFilter} ①）。
 */
@ConditionalOnGuardEntry(PortalFace.INTERNAL)
@Configuration(proxyBeanMethods = false)
public class InternalGuardConfig {

    @Bean
    FilterRegistrationBean<GuardFilter> internalGuardFilter(GuardFilters guards) {
        FilterRegistrationBean<GuardFilter> reg = new FilterRegistrationBean<>(guards.forEntry(PortalFace.INTERNAL));
        reg.addUrlPatterns(PortalFace.INTERNAL.prefix() + "/*");
        return reg;
    }
}
