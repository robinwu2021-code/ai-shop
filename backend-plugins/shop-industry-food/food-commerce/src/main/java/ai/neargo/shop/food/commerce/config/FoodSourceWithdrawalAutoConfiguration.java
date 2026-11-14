package ai.neargo.shop.food.commerce.config;

import ai.neargo.shop.food.commerce.port.FoodSourceWithdrawalProvider;
import ai.neargo.shop.food.service.MenuWithdrawalSourceService;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

@AutoConfiguration(afterName = "ai.neargo.shop.food.config.FoodMenuWithdrawalAutoConfiguration")
@ConditionalOnProperty(
        name = {"shop.food.enabled", "shop.commerce.catalog.withdrawal.enabled"},
        havingValue = "true")
public class FoodSourceWithdrawalAutoConfiguration {
    @Bean
    @ConditionalOnBean(MenuWithdrawalSourceService.class)
    FoodSourceWithdrawalProvider foodSourceWithdrawalProvider(
            MenuWithdrawalSourceService original) {
        return new FoodSourceWithdrawalProvider(original, Clock.systemUTC());
    }
}
