package ai.neargo.shop.food.server.config;

import ai.neargo.shop.food.config.FoodAutoConfiguration;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** 独立服务仅导入餐饮本域；主库、schema 和远程 Port 由后续切片接线。 */
@Configuration(proxyBeanMethods = false)
@Import(FoodAutoConfiguration.class)
public class FoodServerConfiguration {

    /** 工程可装配不等于可营业；在真实就绪合同落地前保持关闭。 */
    @Bean
    HealthIndicator foodReadiness() {
        return () -> Health.outOfService().withDetail("reason", "food-schema-and-ports-pending").build();
    }
}
