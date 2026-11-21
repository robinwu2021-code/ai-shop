package ai.neargo.inventory.svc;

import ai.neargo.inventory.domain.service.ServicePlatformService;
import ai.neargo.inventory.guard.AdminTokenFilter;
import ai.neargo.inventory.guard.InstanceGuard;
import ai.neargo.inventory.guard.InstanceProperties;
import ai.neargo.inventory.guard.InventoryAuthFilter;
import ai.neargo.svc.guard.SignedContextGuardFilter;
import ai.neargo.svc.guard.SignedContextVerifier;
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

    /**
     * ai-shop 网关的签名上下文（谁、哪一端、哪家店、判过哪些功能点）：<b>只比对、不拒绝</b>
     * （ai-shop TDD-网关层与统一权限 §2.7、§2.22）。排在凭证过滤器之前；验过的上下文放进 {@code SignedContextHolder}，
     * 对不上只记日志。密钥与 ai-shop 的 {@code shop.gateway.signing.key} 是同一把，没配就不验。
     */
    @Bean
    FilterRegistrationBean<SignedContextGuardFilter> gatewaySignedContextGuard(
            @Value("${inventory.gateway.signing-key:}") String key,
            @Value("${inventory.gateway.signed-context:observe}") String mode) {
        return signedContextGuard(key, mode);
    }

    /** 只有「关」与「只比对」两档：写成别的（包括 enforce）一律按关。打开拒绝要另行确认。 */
    static FilterRegistrationBean<SignedContextGuardFilter> signedContextGuard(String key, String mode) {
        boolean observe = key != null && !key.isBlank() && "observe".equalsIgnoreCase(mode);
        FilterRegistrationBean<SignedContextGuardFilter> bean = new FilterRegistrationBean<>(new SignedContextGuardFilter(
                new SignedContextVerifier(observe ? key.trim() : "unused"), observe ? "observe" : "off",
                InventoryAuthFilter.PATH_PREFIX));
        bean.addUrlPatterns(InventoryAuthFilter.PATH_PREFIX + "*");
        bean.setOrder(5);
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
