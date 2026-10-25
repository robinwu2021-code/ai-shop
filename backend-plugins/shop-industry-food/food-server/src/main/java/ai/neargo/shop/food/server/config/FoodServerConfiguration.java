package ai.neargo.shop.food.server.config;

import ai.neargo.shop.common.AuditMetaObjectHandler;
import ai.neargo.shop.common.config.PrimaryPersistenceConfiguration;
import ai.neargo.shop.food.channel.config.FoodCatalogClientConfiguration;
import ai.neargo.shop.food.config.FoodAutoConfiguration;
import ai.neargo.shop.food.config.FoodSchemaReady;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort;
import ai.neargo.shop.spi.inventory.MaterialCatalogQueryPort;
import ai.neargo.shop.spi.printing.PrintPort;
import ai.neargo.shop.spi.trade.IndustryOrderPort;
import ai.neargo.shop.spi.trade.PricingCheckoutPort;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.ArrayList;

/** 独立服务仅导入餐饮本域；主库使用平台公共装配，远程 Port 后续切片接线。 */
@Configuration(proxyBeanMethods = false)
@Import({
    PrimaryPersistenceConfiguration.class,
    FoodAutoConfiguration.class,
    FoodCatalogClientConfiguration.class
})
public class FoodServerConfiguration {

    @Bean
    @ConditionalOnMissingBean(MetaObjectHandler.class)
    AuditMetaObjectHandler auditMetaObjectHandler() {
        return new AuditMetaObjectHandler();
    }

    /**
     * Readiness proves schema and unique typed contracts; external owner availability has its own
     * health checks.
     */
    @Bean
    HealthIndicator foodReadiness(
            ObjectProvider<FoodSchemaReady> schemaProvider,
            ObjectProvider<IndustryOrderPort> orders,
            ObjectProvider<PricingCheckoutPort> pricing,
            ObjectProvider<InventoryDemandPlanPort> inventory,
            ObjectProvider<MaterialCatalogQueryPort> materials,
            ObjectProvider<PrintPort> printing) {
        return () -> {
            FoodSchemaReady schema = schemaProvider.getIfAvailable();
            var missing = new ArrayList<String>();
            if (schema == null || !schema.ready()) missing.add("primary-schema");
            unique(orders, "original-order", missing);
            unique(pricing, "shared-pricing", missing);
            unique(inventory, "exact-inventory", missing);
            unique(materials, "inventory-catalog", missing);
            unique(printing, "printing", missing);
            return (missing.isEmpty() ? Health.up() : Health.outOfService())
                    .withDetail(
                            "schemaVersion",
                            schema == null || schema.version() == null ? "none" : schema.version())
                    .withDetail("missingContracts", missing)
                    .withDetail("check", "schema-and-typed-contracts")
                    .build();
        };
    }

    private static void unique(ObjectProvider<?> provider, String name, ArrayList<String> missing) {
        if (provider.orderedStream().limit(2).count() != 1) missing.add(name);
    }
}
