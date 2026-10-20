package ai.neargo.shop.food.server.config;

import ai.neargo.shop.food.config.FoodAutoConfiguration;
import ai.neargo.shop.food.config.FoodSchemaReady;
import ai.neargo.shop.common.config.PrimaryPersistenceConfiguration;
import ai.neargo.shop.common.AuditMetaObjectHandler;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** 独立服务仅导入餐饮本域；主库使用平台公共装配，远程 Port 后续切片接线。 */
@Configuration(proxyBeanMethods = false)
@Import({PrimaryPersistenceConfiguration.class, FoodAutoConfiguration.class})
public class FoodServerConfiguration {

    @Bean
    @ConditionalOnMissingBean(MetaObjectHandler.class)
    AuditMetaObjectHandler auditMetaObjectHandler() { return new AuditMetaObjectHandler(); }

    /** 工程可装配不等于可营业；在真实就绪合同落地前保持关闭。 */
    @Bean
    HealthIndicator foodReadiness(ObjectProvider<FoodSchemaReady> schemaProvider) {
        return () -> {
            FoodSchemaReady schema = schemaProvider.getIfAvailable();
            return Health.outOfService().withDetail("schemaReady", schema != null && schema.ready())
                    .withDetail("schemaVersion", schema == null || schema.version() == null ? "none" : schema.version())
                    .withDetail("reason", schema == null ? "food-disabled" : "food-remote-ports-pending").build();
        };
    }
}
