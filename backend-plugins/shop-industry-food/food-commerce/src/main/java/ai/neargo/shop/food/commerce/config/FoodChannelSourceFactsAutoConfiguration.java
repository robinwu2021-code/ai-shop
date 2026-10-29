package ai.neargo.shop.food.commerce.config;

import ai.neargo.shop.food.commerce.port.FoodChannelSourceFactsPortImpl;
import ai.neargo.shop.food.service.ConfigRevisionService;
import ai.neargo.shop.food.spi.DiningChannelSourceFactsPort;
import ai.neargo.shop.spi.commerce.ChannelOrderSourceEvidencePort;
import ai.neargo.shop.spi.inventory.MaterialCatalogQueryPort;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

/**
 * The FOOD source is installed only when actual CIM, historical FOOD and physical material owners
 * exist.
 */
@AutoConfiguration(
        afterName = {
            "ai.neargo.shop.food.config.FoodAutoConfiguration",
            "ai.neargo.shop.food.config.FoodWebAutoConfiguration",
            "ai.neargo.shop.commerce.config.CommerceCoreAutoConfiguration",
            "ai.neargo.shop.commerce.channel.CommerceOrderEvidenceClientAutoConfiguration"
        },
        beforeName = "ai.neargo.shop.food.config.FoodChannelOrderAutoConfiguration")
@ConditionalOnProperty(
        name = {"shop.food.enabled", "shop.food.channel.enabled"},
        havingValue = "true")
public class FoodChannelSourceFactsAutoConfiguration {
    @Bean
    @ConditionalOnBean({
        ChannelOrderSourceEvidencePort.class,
        ConfigRevisionService.class,
        MaterialCatalogQueryPort.class,
        ObjectMapper.class
    })
    @ConditionalOnMissingBean(DiningChannelSourceFactsPort.class)
    FoodChannelSourceFactsPortImpl foodChannelSourceFactsPort(
            ChannelOrderSourceEvidencePort evidence,
            ConfigRevisionService revisions,
            MaterialCatalogQueryPort materials,
            ObjectMapper json,
            ObjectProvider<Clock> clocks) {
        return new FoodChannelSourceFactsPortImpl(
                evidence, revisions, materials, json, clocks.getIfAvailable(Clock::systemUTC));
    }
}
