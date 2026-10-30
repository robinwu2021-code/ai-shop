package ai.neargo.shop.food.commerce.config;

import ai.neargo.shop.food.commerce.event.FoodChannelFulfillmentEventConsumer;
import ai.neargo.shop.food.service.DiningChannelFulfillmentSignalPort;
import ai.neargo.shop.spi.commerce.ChannelFulfillmentTriggerPort;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

/**
 * Explicit original-event activation requires genuine native signal and CIM intake owners; it
 * creates no new scheduler.
 */
@AutoConfiguration(
        afterName = {
            "ai.neargo.shop.food.config.FoodChannelFulfillmentSignalAutoConfiguration",
            "ai.neargo.shop.food.commerce.config.FoodFulfillmentSignalClientAutoConfiguration",
            "ai.neargo.shop.commerce.config.CommerceFulfillmentTriggerAutoConfiguration",
            "ai.neargo.shop.commerce.channel.CommerceFulfillmentTriggerClientAutoConfiguration"
        })
@ConditionalOnProperty(name = "shop.food.commerce.fulfillment-events.enabled", havingValue = "true")
public class FoodChannelFulfillmentEventsAutoConfiguration {
    @Bean
    @ConditionalOnBean({
        DiningChannelFulfillmentSignalPort.class,
        ChannelFulfillmentTriggerPort.class,
        ObjectMapper.class
    })
    FoodChannelFulfillmentEventConsumer foodChannelFulfillmentEventConsumer(
            DiningChannelFulfillmentSignalPort signals,
            ChannelFulfillmentTriggerPort trigger,
            ObjectMapper json,
            ObjectProvider<Clock> clocks) {
        return new FoodChannelFulfillmentEventConsumer(
                signals, trigger, json, clocks.getIfAvailable(Clock::systemUTC));
    }
}
