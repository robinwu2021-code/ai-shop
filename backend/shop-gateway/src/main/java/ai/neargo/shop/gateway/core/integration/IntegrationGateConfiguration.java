package ai.neargo.shop.gateway.core.integration;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 把 {@link IntegrationGateFilter} 挂在安全过滤器链（order -100）之前。 */
@Configuration(proxyBeanMethods = false)
public class IntegrationGateConfiguration {

    /** 安全过滤器链的默认顺序是 -100；排在它前面，关掉的对象不经认证就回 404。 */
    static final int ORDER = -105;

    @Bean
    FilterRegistrationBean<IntegrationGateFilter> integrationGateFilter(IntegrationRegistry registry) {
        FilterRegistrationBean<IntegrationGateFilter> bean = new FilterRegistrationBean<>(new IntegrationGateFilter(registry));
        bean.setOrder(ORDER);
        bean.addUrlPatterns("/*");
        return bean;
    }
}
