package ai.neargo.shop.food.commerce.config;

import ai.neargo.shop.commerce.service.ChannelCatalogAuthorityService;
import ai.neargo.shop.food.commerce.service.FoodCatalogBindingService;
import ai.neargo.shop.food.commerce.service.impl.FoodCatalogBindingServiceImpl;
import ai.neargo.shop.food.service.ConfigRevisionService;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

/**
 * Explicit opt-in avoids activating FOOD catalog composition in existing retail or dining hosts.
 */
@AutoConfiguration(
        afterName = {
            "ai.neargo.shop.food.config.FoodAutoConfiguration",
            "ai.neargo.shop.food.config.FoodWebAutoConfiguration",
            "ai.neargo.shop.commerce.config.CommerceCoreAutoConfiguration",
            "ai.neargo.shop.commerce.channel.CommerceAuthorityClientAutoConfiguration",
            "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration"
        })
@ConditionalOnProperty(
        name = {"shop.food.enabled", "shop.food.commerce.binding.enabled"},
        havingValue = "true")
public class FoodCatalogBindingAutoConfiguration {
    @Bean
    @ConditionalOnBean({
        ConfigRevisionService.class,
        ChannelCatalogAuthorityService.class,
        ObjectMapper.class
    })
    @ConditionalOnMissingBean(FoodCatalogBindingService.class)
    FoodCatalogBindingService foodCatalogBindingService(
            ObjectMapper json,
            ConfigRevisionService menus,
            @Lazy ChannelCatalogAuthorityService authorities,
            ObjectProvider<Clock> clocks) {
        return new FoodCatalogBindingServiceImpl(
                json, menus, authorities, clocks.getIfAvailable(Clock::systemUTC));
    }
}
