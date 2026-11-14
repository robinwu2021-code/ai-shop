package ai.neargo.shop.food.commerce.config;

import ai.neargo.shop.food.commerce.api.internal.FoodCommerceInternalSecurity;
import ai.neargo.shop.food.commerce.api.internal.InternalFoodSourceWithdrawalController;
import ai.neargo.shop.food.commerce.port.FoodSourceWithdrawalProvider;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

@AutoConfiguration(
        after = {
            FoodSourceWithdrawalAutoConfiguration.class,
            FoodCatalogInternalAutoConfiguration.class
        })
@ConditionalOnProperty(
        name = {"shop.food.commerce.internal.enabled", "shop.commerce.catalog.withdrawal.enabled"},
        havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class FoodWithdrawalInternalAutoConfiguration {
    @Bean
    @ConditionalOnBean({
        FoodCommerceInternalSecurity.class,
        FoodSourceWithdrawalProvider.class,
        ObjectMapper.class
    })
    InternalFoodSourceWithdrawalController internalFoodSourceWithdrawalController(
            FoodCommerceInternalSecurity security,
            FoodSourceWithdrawalProvider owner,
            ObjectMapper json) {
        return new InternalFoodSourceWithdrawalController(security, owner, json, Clock.systemUTC());
    }
}
