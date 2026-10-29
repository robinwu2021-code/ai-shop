package ai.neargo.inventory.svc;

import ai.neargo.inventory.guard.InstanceGuard;
import ai.neargo.inventory.guard.InstanceProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
}
