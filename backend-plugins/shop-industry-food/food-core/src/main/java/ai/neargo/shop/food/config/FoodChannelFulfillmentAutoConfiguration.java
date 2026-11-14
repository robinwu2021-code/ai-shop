package ai.neargo.shop.food.config;

import ai.neargo.shop.food.mapper.DiningChannelMappers.ChannelOrderSourceMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CoreLineAllocationMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CoreOrderBindingMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderBatchMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderLineMapper;
import ai.neargo.shop.food.service.DiningChannelFulfillmentService;
import ai.neargo.shop.food.service.KitchenTaskService;
import ai.neargo.shop.food.service.impl.DiningChannelFulfillmentServiceImpl;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

@AutoConfiguration(after = FoodChannelOrderAutoConfiguration.class)
@ConditionalOnProperty(
        name = {"shop.food.enabled", "shop.food.channel.enabled"},
        havingValue = "true")
public class FoodChannelFulfillmentAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(DiningChannelFulfillmentService.class)
    DiningChannelFulfillmentService diningChannelFulfillmentService(
            ChannelOrderSourceMapper sources,
            OrderBatchMapper batches,
            OrderLineMapper lines,
            CoreOrderBindingMapper bindings,
            CoreLineAllocationMapper allocations,
            KitchenTaskService kitchen,
            ObjectMapper json,
            ObjectProvider<Clock> clocks) {
        return new DiningChannelFulfillmentServiceImpl(
                sources,
                batches,
                lines,
                bindings,
                allocations,
                kitchen,
                json,
                clocks.getIfAvailable(Clock::systemUTC));
    }
}
