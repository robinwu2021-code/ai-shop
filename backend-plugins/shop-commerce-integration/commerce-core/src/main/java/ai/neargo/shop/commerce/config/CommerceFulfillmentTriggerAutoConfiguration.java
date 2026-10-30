package ai.neargo.shop.commerce.config;

import ai.neargo.shop.commerce.mapper.CommerceMappers.OrderLinkMapper;
import ai.neargo.shop.commerce.service.ChannelFulfillmentService;
import ai.neargo.shop.commerce.service.impl.ChannelFulfillmentTriggerPortImpl;
import ai.neargo.shop.commerce.service.impl.CommerceCommands;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * The generic original-reference intake uses the same persisted fulfillment service and the sole
 * existing recovery workflow.
 */
@AutoConfiguration(after = CommerceCoreAutoConfiguration.class)
@ConditionalOnProperty(name = "shop.commerce.enabled", havingValue = "true")
public class CommerceFulfillmentTriggerAutoConfiguration {
    @Bean
    ChannelFulfillmentTriggerPortImpl channelFulfillmentTriggerPort(
            OrderLinkMapper orders,
            CommerceCommands commands,
            ChannelFulfillmentService fulfillment) {
        return new ChannelFulfillmentTriggerPortImpl(orders, commands, fulfillment);
    }
}
