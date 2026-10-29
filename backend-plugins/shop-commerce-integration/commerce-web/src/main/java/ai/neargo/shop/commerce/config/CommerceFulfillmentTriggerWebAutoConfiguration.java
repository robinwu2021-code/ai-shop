package ai.neargo.shop.commerce.config;

import ai.neargo.shop.commerce.api.internal.InternalChannelFulfillmentTriggerController;
import ai.neargo.shop.spi.commerce.ChannelFulfillmentTriggerPort;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

/** Private trigger activation is explicit and requires the actual persisted CIM intake owner. */
@AutoConfiguration(
        afterName = "ai.neargo.shop.commerce.config.CommerceFulfillmentTriggerAutoConfiguration")
@ConditionalOnProperty(name = "shop.commerce.fulfillment-trigger.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class CommerceFulfillmentTriggerWebAutoConfiguration {
    @Bean
    @ConditionalOnBean(ChannelFulfillmentTriggerPort.class)
    InternalChannelFulfillmentTriggerController internalChannelFulfillmentTriggerController(
            @Value("${shop.services.internal-token:}") String token,
            @Value("${shop.food.scope-key:}") String key,
            @Value("${shop.food.scope-key-previous:}") String previous,
            ChannelFulfillmentTriggerPort trigger,
            ObjectMapper json,
            ObjectProvider<Clock> clocks) {
        return new InternalChannelFulfillmentTriggerController(
                token, key, previous, trigger, json, clocks.getIfAvailable(Clock::systemUTC));
    }
}
