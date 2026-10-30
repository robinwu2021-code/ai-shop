package ai.neargo.shop.food.commerce.config;

import ai.neargo.shop.food.commerce.port.FoodSourceValidationProviderImpl;
import ai.neargo.shop.food.service.ConfigRevisionService;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.ObjectMapper;

/** FOOD may contribute its owner capability independently of whether CIM persistence runs in this process. */
@AutoConfiguration(afterName = {"ai.neargo.shop.food.config.FoodAutoConfiguration",
        "ai.neargo.shop.food.config.FoodWebAutoConfiguration",
        "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration"},
        beforeName = "ai.neargo.shop.commerce.config.CommerceSourceValidationAutoConfiguration")
@ConditionalOnProperty(name = {"shop.food.enabled", "shop.food.commerce.source-validation.enabled"},
        havingValue = "true")
public class FoodSourceValidationAutoConfiguration {
    @Bean
    @ConditionalOnBean({ConfigRevisionService.class, ObjectMapper.class})
    FoodSourceValidationProviderImpl foodSourceValidationProvider(ObjectProvider<ConfigRevisionService> menus,
            ObjectMapper json, ObjectProvider<Clock> clocks) {
        return new FoodSourceValidationProviderImpl(menus::getObject, json, clocks.getIfAvailable(Clock::systemUTC));
    }
}
