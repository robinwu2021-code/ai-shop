package ai.neargo.shop.food.commerce.config;

import ai.neargo.shop.commerce.service.ChannelCatalogAuthorityService;
import ai.neargo.shop.food.commerce.port.FoodCatalogSourcePortImpl;
import ai.neargo.shop.food.commerce.service.FoodCatalogPricePolicyService;
import ai.neargo.shop.food.service.ConfigRevisionService;
import ai.neargo.shop.food.service.StoreCalendarSourceService;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;
import ai.neargo.shop.spi.commerce.ChannelSourceWithdrawalProvider;
import ai.neargo.shop.spi.media.MediaPublicSourcePort;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

/**
 * Complete owner capture is explicitly enabled independently from public routes and remote workers.
 */
@AutoConfiguration(
        afterName = {
            "ai.neargo.shop.food.commerce.config.FoodCatalogPricePolicyAutoConfiguration",
            "ai.neargo.shop.food.commerce.config.FoodCatalogBindingAutoConfiguration",
            "ai.neargo.shop.food.config.FoodAutoConfiguration",
            "ai.neargo.shop.food.config.FoodWebAutoConfiguration",
            "ai.neargo.shop.commerce.channel.CommerceAuthorityClientAutoConfiguration"
        })
@ConditionalOnProperty(
        name = {"shop.food.enabled", "shop.food.commerce.catalog.enabled"},
        havingValue = "true")
public class FoodCatalogSourceAutoConfiguration {
    @Bean
    @ConditionalOnBean({
        ConfigRevisionService.class,
        ChannelCatalogAuthorityService.class,
        FoodCatalogPricePolicyService.class,
        StoreCalendarSourceService.class,
        GoodsCatalogQueryPort.class,
        MediaPublicSourcePort.class,
        ObjectMapper.class
    })
    @ConditionalOnMissingBean(FoodCatalogSourcePortImpl.class)
    FoodCatalogSourcePortImpl foodCatalogSourcePort(
            ConfigRevisionService menus,
            @Lazy ChannelCatalogAuthorityService authorities,
            FoodCatalogPricePolicyService prices,
            StoreCalendarSourceService calendars,
            GoodsCatalogQueryPort goods,
            MediaPublicSourcePort media,
            ObjectMapper json,
            ObjectProvider<Clock> clocks,
            ObjectProvider<CatalogPriceSourcePort> scopePrices,
            ObjectProvider<ChannelSourceWithdrawalProvider> withdrawals,
            @Value("${shop.commerce.catalog.withdrawal.enabled:false}") boolean withdrawalEnabled) {
        var source =
                new FoodCatalogSourcePortImpl(
                        menus,
                        authorities,
                        prices,
                        calendars,
                        goods,
                        media,
                        json,
                        clocks.getIfAvailable(Clock::systemUTC));
        source.withdrawalSupport(
                withdrawalEnabled,
                scopePrices::getObject,
                () -> {
                    var providers =
                            withdrawals
                                    .orderedStream()
                                    .filter(provider -> provider.owner() == Owner.FOOD)
                                    .toList();
                    if (providers.size() > 1)
                        throw new IllegalStateException("Duplicate original FOOD withdrawal owner");
                    return providers.isEmpty() ? null : providers.getFirst();
                });
        return source;
    }
}
