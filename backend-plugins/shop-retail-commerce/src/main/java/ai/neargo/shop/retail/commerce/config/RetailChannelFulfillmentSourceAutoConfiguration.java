package ai.neargo.shop.retail.commerce.config;

import ai.neargo.shop.retail.commerce.port.RetailChannelFulfillmentSourcePortImpl;
import ai.neargo.shop.spi.trade.IndustryChannelFulfillmentSourcePort;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

/**
 * Local original owner mode and remote mode are exclusive; absence never installs a successful
 * fallback.
 */
@AutoConfiguration(afterName = "ai.neargo.shop.trade.config.TradeCoreAutoConfiguration")
@ConditionalOnProperty(
        name = {"shop.retail.commerce.enabled", "shop.retail.commerce.fulfillment.enabled"},
        havingValue = "true")
public class RetailChannelFulfillmentSourceAutoConfiguration {
    @Bean
    @ConditionalOnBean(IndustryChannelFulfillmentSourcePort.class)
    @ConditionalOnProperty(
            name = "shop.commerce.retail-fulfillment.source-mode",
            havingValue = "local",
            matchIfMissing = true)
    RetailChannelFulfillmentSourcePortImpl retailChannelFulfillmentSourcePort(
            ObjectProvider<IndustryChannelFulfillmentSourcePort> source,
            ObjectProvider<Clock> clocks) {
        return new RetailChannelFulfillmentSourcePortImpl(
                source::getObject, clocks.getIfAvailable(Clock::systemUTC));
    }
}
