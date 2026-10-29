package ai.neargo.shop.retail.commerce.config;

import ai.neargo.shop.event.outbox.OutboxRouteContribution;
import ai.neargo.shop.retail.commerce.event.RetailChannelFulfillmentEventConsumer;
import ai.neargo.shop.spi.commerce.ChannelFulfillmentTriggerPort;
import ai.neargo.shop.spi.trade.IndustryChannelFulfillmentSourcePort;
import ai.neargo.shop.spi.trade.RetailChannelFulfillmentSealed;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.Set;

/**
 * Opt-in original-event bridge uses existing outbox recovery and cannot authorize local stock
 * changes.
 */
@AutoConfiguration(
        afterName = {
            "ai.neargo.shop.trade.config.TradeCoreAutoConfiguration",
            "ai.neargo.shop.commerce.config.CommerceFulfillmentTriggerAutoConfiguration",
            "ai.neargo.shop.commerce.channel.CommerceFulfillmentTriggerClientAutoConfiguration"
        })
@ConditionalOnProperty(
        name = {
            "shop.retail.commerce.enabled",
            "shop.retail.commerce.fulfillment.enabled",
            "shop.retail.commerce.fulfillment-events.enabled"
        },
        havingValue = "true")
public class RetailChannelFulfillmentEventsAutoConfiguration {
    @Bean
    @ConditionalOnBean({
        IndustryChannelFulfillmentSourcePort.class,
        ChannelFulfillmentTriggerPort.class,
        ObjectMapper.class
    })
    RetailChannelFulfillmentEventConsumer retailChannelFulfillmentEventConsumer(
            IndustryChannelFulfillmentSourcePort source,
            ChannelFulfillmentTriggerPort trigger,
            ObjectMapper json,
            ObjectProvider<Clock> clocks) {
        return new RetailChannelFulfillmentEventConsumer(
                source, trigger, json, clocks.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnBean(RetailChannelFulfillmentEventConsumer.class)
    OutboxRouteContribution retailChannelFulfillmentRoutes() {
        return () -> Set.of("PLATFORM:" + RetailChannelFulfillmentSealed.TYPE);
    }
}
