package ai.neargo.shop.commerce.config;

import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;
import ai.neargo.shop.spi.commerce.OriginalChannelAfterSaleSourceAdapter;
import ai.neargo.shop.spi.trade.IndustryChannelAfterSalePort;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

@AutoConfiguration(before = CommerceAfterSaleAutoConfiguration.class)
@ConditionalOnProperty(name = "shop.commerce.enabled", havingValue = "true")
@ConditionalOnProperty(
        name = "shop.commerce.after-sale.source-mode",
        havingValue = "embedded",
        matchIfMissing = true)
@ConditionalOnBean(IndustryChannelAfterSalePort.class)
public class CommerceAfterSaleSourceAutoConfiguration {
    @Bean
    OriginalChannelAfterSaleSourceAdapter originalFoodAfterSaleSource(
            IndustryChannelAfterSalePort original, ObjectProvider<Clock> clocks) {
        return new OriginalChannelAfterSaleSourceAdapter(
                Owner.FOOD, original::get, clocks.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    OriginalChannelAfterSaleSourceAdapter originalRetailAfterSaleSource(
            IndustryChannelAfterSalePort original, ObjectProvider<Clock> clocks) {
        return new OriginalChannelAfterSaleSourceAdapter(
                Owner.RETAIL, original::get, clocks.getIfAvailable(Clock::systemUTC));
    }
}
