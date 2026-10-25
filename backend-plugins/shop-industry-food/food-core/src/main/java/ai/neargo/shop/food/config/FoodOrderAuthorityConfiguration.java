package ai.neargo.shop.food.config;

import ai.neargo.shop.food.service.ConfigRevisionService;
import ai.neargo.shop.food.service.DiningIdentityService;
import ai.neargo.shop.food.service.DiningOrderAuthorityService;
import ai.neargo.shop.food.service.DiningPricingFactsService;
import ai.neargo.shop.food.service.impl.DiningOrderAuthorityServiceImpl;
import ai.neargo.shop.spi.inventory.MaterialCatalogQueryPort;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;

import tools.jackson.databind.ObjectMapper;

@AutoConfiguration(
        afterName = {
            "ai.neargo.shop.food.config.FoodAutoConfiguration",
            "ai.neargo.shop.food.config.DiningIdentityAutoConfiguration"
        })
@ConditionalOnFoodEnabled
@Conditional(FoodPersistenceConfiguration.PersistenceEnabled.class)
public class FoodOrderAuthorityConfiguration {
    @Bean
    @ConditionalOnMissingBean(DiningOrderAuthorityService.class)
    DiningOrderAuthorityService diningOrderAuthorityService(
            DiningPricingFactsService facts,
            DiningIdentityService identities,
            ConfigRevisionService revisions,
            MaterialCatalogQueryPort materials,
            ObjectMapper json) {
        return new DiningOrderAuthorityServiceImpl(facts, identities, revisions, materials, json);
    }
}
