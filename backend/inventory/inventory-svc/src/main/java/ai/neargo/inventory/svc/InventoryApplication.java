package ai.neargo.inventory.svc;

import ai.neargo.inventory.domain.service.ServicePlatformService;
import ai.neargo.inventory.guard.AdminTokenFilter;
import ai.neargo.inventory.guard.InstanceGuard;
import ai.neargo.inventory.guard.InstanceProperties;
import ai.neargo.inventory.guard.InventoryAuthFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

/**
 * 进销存独立服务的进程入口。
 *
 * <p>测试环境一套、生产电商套与设备套，**都是这一个程序包**；差异只在环境文件
 * （实例代号、放行系统、库连接）。领域经 {@code InventoryDomainAutoConfiguration} 装配，
 * 这里不扫领域包。
 */
@SpringBootApplication
@EnableConfigurationProperties(InstanceProperties.class)
public class InventoryApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryApplication.class, args);
    }

    @Bean
    InstanceGuard instanceGuard(InstanceProperties instance) {
        return new InstanceGuard(instance);
    }

    /** 服务入口：每个请求认凭证、查实例放行、查写入方（AC-15 / AC-19 / AC-28）。 */
    @Bean
    FilterRegistrationBean<InventoryAuthFilter> inventoryAuthFilter(ServicePlatformService platform,
                                                                   InstanceGuard instanceGuard) {
        FilterRegistrationBean<InventoryAuthFilter> bean =
                new FilterRegistrationBean<>(new InventoryAuthFilter(platform, instanceGuard));
        bean.addUrlPatterns(InventoryAuthFilter.PATH_PREFIX + "*");
        bean.setOrder(10);
        return bean;
    }

    /** 平台运维入口：每套一把运维令牌，没配就关闭。 */
    @Bean
    FilterRegistrationBean<AdminTokenFilter> adminTokenFilter(@Value("${inventory.admin.token:}") String token) {
        FilterRegistrationBean<AdminTokenFilter> bean = new FilterRegistrationBean<>(new AdminTokenFilter(token));
        bean.addUrlPatterns(AdminTokenFilter.PATH_PREFIX + "*");
        bean.setOrder(10);
        return bean;
    }
}
