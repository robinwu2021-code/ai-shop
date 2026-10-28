package ai.neargo.shop.food.commerce.config;

import ai.neargo.shop.commerce.service.ChannelCatalogAuthorityService;
import ai.neargo.shop.food.commerce.port.MenuChannelAuthorityPortImpl;
import ai.neargo.shop.food.commerce.service.FoodCatalogBindingService;
import ai.neargo.shop.food.commerce.service.FoodCatalogPricePolicyService;
import ai.neargo.shop.food.commerce.service.impl.FoodCatalogPricePolicyServiceImpl;
import ai.neargo.shop.food.service.MenuCatalogPriceService;
import ai.neargo.shop.food.service.MenuChannelBindingService;
import ai.neargo.shop.food.spi.MenuChannelAuthorityPort;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

/** Configuration writes and catalog reads are enabled explicitly, without activating any live channel. */
@AutoConfiguration(afterName = {
    "ai.neargo.shop.food.commerce.config.FoodCatalogBindingAutoConfiguration",
    "ai.neargo.shop.food.channel.config.FoodOfferClientConfiguration",
    "ai.neargo.shop.food.config.FoodAutoConfiguration",
    "ai.neargo.shop.food.config.FoodWebAutoConfiguration",
    "ai.neargo.shop.commerce.config.CommerceCoreAutoConfiguration",
    "ai.neargo.shop.commerce.channel.CommerceAuthorityClientAutoConfiguration",
    "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration"
})
@ConditionalOnProperty(name = {"shop.food.enabled", "shop.food.commerce.price-policy.enabled"}, havingValue = "true")
public class FoodCatalogPricePolicyAutoConfiguration {
    @Bean
    @ConditionalOnBean({MenuChannelBindingService.class, ChannelCatalogAuthorityService.class,
            CatalogPriceSourcePort.class})
    @ConditionalOnMissingBean(MenuChannelAuthorityPort.class)
    MenuChannelAuthorityPort menuChannelAuthorityPort(
            MenuChannelBindingService bindings, ChannelCatalogAuthorityService authorities,
            CatalogPriceSourcePort products, ObjectProvider<Clock> clocks) {
        return new MenuChannelAuthorityPortImpl(
                bindings, authorities, products, clocks.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnBean({FoodCatalogBindingService.class, MenuCatalogPriceService.class, ObjectMapper.class})
    @ConditionalOnMissingBean(FoodCatalogPricePolicyService.class)
    FoodCatalogPricePolicyService foodCatalogPricePolicyService(
            FoodCatalogBindingService bindings, MenuCatalogPriceService prices, ObjectMapper json,
            ObjectProvider<Clock> clocks) {
        return new FoodCatalogPricePolicyServiceImpl(bindings, prices, json, clocks.getIfAvailable(Clock::systemUTC));
    }
}
