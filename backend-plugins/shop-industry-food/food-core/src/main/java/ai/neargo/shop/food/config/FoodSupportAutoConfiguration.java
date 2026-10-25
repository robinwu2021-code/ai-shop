package ai.neargo.shop.food.config;

import ai.neargo.shop.food.service.BusinessOperationsService;
import ai.neargo.shop.food.service.DiningIdentityService;
import ai.neargo.shop.food.service.DiningOrderService;
import ai.neargo.shop.food.service.FoodKdsService;
import ai.neargo.shop.food.service.FoodSupportService;
import ai.neargo.shop.food.service.impl.FoodSupportServiceImpl;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort;
import ai.neargo.shop.spi.inventory.MaterialCatalogQueryPort;
import ai.neargo.shop.spi.printing.PrintPort;
import ai.neargo.shop.spi.trade.IndustryOrderPort;
import ai.neargo.shop.spi.trade.PricingCheckoutPort;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;

import java.util.Map;

/** Exposes original runtime readiness through an API-only contract. */
@AutoConfiguration(
        afterName = {
            "ai.neargo.shop.food.config.FoodAutoConfiguration",
            "ai.neargo.shop.food.config.FoodBusinessOperationsConfiguration"
        })
@ConditionalOnFoodEnabled
@Conditional(FoodPersistenceConfiguration.PersistenceEnabled.class)
public class FoodSupportAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(FoodSupportService.class)
    FoodSupportService foodSupportService(
            FoodProperties properties,
            ObjectProvider<FoodSchemaReady> schemas,
            ObjectProvider<IndustryOrderPort> orders,
            ObjectProvider<PricingCheckoutPort> pricing,
            ObjectProvider<InventoryDemandPlanPort> inventory,
            ObjectProvider<MaterialCatalogQueryPort> materials,
            ObjectProvider<PrintPort> printing,
            ObjectProvider<DiningOrderService> dining,
            ObjectProvider<DiningIdentityService> identity,
            ObjectProvider<FoodKdsService> kds,
            BusinessOperationsService operations) {
        return new FoodSupportServiceImpl(
                properties,
                schemas,
                Map.of(
                        "original-order",
                        orders,
                        "shared-pricing",
                        pricing,
                        "exact-inventory",
                        inventory,
                        "inventory-catalog",
                        materials,
                        "printing",
                        printing,
                        "dining-order",
                        dining,
                        "dining-identity",
                        identity,
                        "kds",
                        kds),
                operations);
    }
}
