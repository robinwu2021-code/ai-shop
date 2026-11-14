package ai.neargo.shop.pay.svc;

import ai.neargo.shop.pay.api.guard.ConditionalOnGuardEntry;
import ai.neargo.shop.pay.api.guard.PortalFace;
import ai.neargo.shop.pay.api.guard.core.GuardFilter;
import ai.neargo.shop.pay.api.guard.core.GuardFilters;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 独立形态下对外入口的守卫挂载（开发计划 M7.1 / D1）：这个进程没有 Spring Security，守卫按入口前缀注册成 servlet filter ——
 * 与 {@code shop-app} 里挂在各条安全链上的是同一个 {@link GuardFilter}。内部入口由 {@code InternalGuardConfig} 挂。
 * 装哪几个看 {@code shop.pay.guard.entries}；没装的入口不注册。
 */
@Configuration(proxyBeanMethods = false)
public class PayGuardFilterConfig {

    @Bean
    @ConditionalOnGuardEntry(PortalFace.OPEN)
    FilterRegistrationBean<GuardFilter> openGuardFilter(GuardFilters guards) {
        return register(guards, PortalFace.OPEN);
    }

    @Bean
    @ConditionalOnGuardEntry(PortalFace.TERMINAL)
    FilterRegistrationBean<GuardFilter> terminalGuardFilter(GuardFilters guards) {
        return register(guards, PortalFace.TERMINAL);
    }

    @Bean
    @ConditionalOnGuardEntry(PortalFace.CALLBACK)
    FilterRegistrationBean<GuardFilter> callbackGuardFilter(GuardFilters guards) {
        return register(guards, PortalFace.CALLBACK);
    }

    @Bean
    @ConditionalOnGuardEntry(PortalFace.CHECKOUT)
    FilterRegistrationBean<GuardFilter> checkoutGuardFilter(GuardFilters guards) {
        return register(guards, PortalFace.CHECKOUT);
    }

    private static FilterRegistrationBean<GuardFilter> register(GuardFilters guards, PortalFace face) {
        FilterRegistrationBean<GuardFilter> reg = new FilterRegistrationBean<>(guards.forEntry(face));
        reg.addUrlPatterns(face.prefix() + "/*");
        reg.setName("payGuard" + face.name());
        return reg;
    }
}
