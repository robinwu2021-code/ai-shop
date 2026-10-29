package ai.neargo.shop.food.config;

import ai.neargo.shop.food.mapper.DiningChannelMappers.ChannelOrderSourceMapper;
import ai.neargo.shop.food.mapper.FoodMappers.KitchenTaskMapper;
import ai.neargo.shop.food.mapper.FoodMappers.KitchenTicketMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderBatchMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderLineMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ServiceEventMapper;
import ai.neargo.shop.food.service.DiningChannelFulfillmentSignalPort;
import ai.neargo.shop.food.service.impl.DiningChannelFulfillmentSignalPortImpl;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

/** Original native event reads remain opt-in with the actual original channel owner. */
@AutoConfiguration(after = FoodChannelFulfillmentAutoConfiguration.class)
@ConditionalOnProperty(
        name = {
            "shop.food.enabled",
            "shop.food.channel.enabled",
            "shop.food.commerce.fulfillment-events.enabled"
        },
        havingValue = "true")
public class FoodChannelFulfillmentSignalAutoConfiguration {
    @Bean
    DiningChannelFulfillmentSignalPort diningChannelFulfillmentSignalPort(
            ServiceEventMapper events,
            KitchenTaskMapper tasks,
            KitchenTicketMapper tickets,
            OrderBatchMapper batches,
            OrderLineMapper lines,
            ChannelOrderSourceMapper sources,
            ObjectMapper json,
            ObjectProvider<Clock> clocks) {
        return new DiningChannelFulfillmentSignalPortImpl(
                events,
                tasks,
                tickets,
                batches,
                lines,
                sources,
                json,
                clocks.getIfAvailable(Clock::systemUTC));
    }
}
