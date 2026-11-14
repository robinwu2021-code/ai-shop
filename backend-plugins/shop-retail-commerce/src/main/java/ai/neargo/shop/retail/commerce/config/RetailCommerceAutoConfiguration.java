package ai.neargo.shop.retail.commerce.config;

import ai.neargo.shop.commerce.service.ChannelCatalogAuthorityService;
import ai.neargo.shop.retail.commerce.mapper.RetailSelectionMapper;
import ai.neargo.shop.retail.commerce.mapper.impl.RetailSelectionMapperImpl;
import ai.neargo.shop.retail.commerce.port.OriginalRetailCatalogAvailability;
import ai.neargo.shop.retail.commerce.port.RetailCatalogAvailabilityPort;
import ai.neargo.shop.retail.commerce.port.RetailCatalogSourcePortImpl;
import ai.neargo.shop.retail.commerce.port.RetailSourceValidationProviderImpl;
import ai.neargo.shop.retail.commerce.service.RetailSelectionManagementService;
import ai.neargo.shop.retail.commerce.service.RetailSelectionService;
import ai.neargo.shop.retail.commerce.service.impl.RetailSelectionManagementServiceImpl;
import ai.neargo.shop.retail.commerce.service.impl.RetailSelectionServiceImpl;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;
import ai.neargo.shop.spi.commerce.ChannelSourceWithdrawalProvider;
import ai.neargo.shop.spi.media.MediaPublicSourcePort;
import ai.neargo.shop.spi.pay.MarketPort;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort;
import ai.neargo.shop.spi.product.RetailProductSourcePort;
import ai.neargo.shop.spi.user.StoreCardQueryPort;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

import javax.sql.DataSource;

/**
 * Optional embedded bridge; no FOOD dependencies and no default successful source/calendar
 * fallback.
 */
@AutoConfiguration(
        afterName = {
            "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
            "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration",
            "ai.neargo.shop.commerce.channel.CommerceAuthorityClientAutoConfiguration"
        },
        beforeName = "ai.neargo.shop.commerce.config.CommerceSourceValidationAutoConfiguration")
@ConditionalOnProperty(name = "shop.retail.commerce.enabled", havingValue = "true")
public class RetailCommerceAutoConfiguration {
    @Bean
    @ConditionalOnBean({DataSource.class, PlatformTransactionManager.class, ObjectMapper.class})
    RetailSelectionMapperImpl retailSelectionRepository(
            DataSource dataSource,
            @Qualifier("transactionManager") PlatformTransactionManager manager,
            ObjectMapper json) {
        return new RetailSelectionMapperImpl(new JdbcTemplate(dataSource), manager, json);
    }

    @Bean
    @ConditionalOnBean({RetailSelectionMapper.class, RetailProductSourcePort.class})
    RetailSourceValidationProviderImpl retailSourceValidationProvider(
            RetailSelectionMapper selections,
            RetailProductSourcePort products,
            ObjectProvider<Clock> clocks) {
        return new RetailSourceValidationProviderImpl(
                selections, products, clocks.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnBean({
        RetailSelectionMapper.class,
        RetailProductSourcePort.class,
        ChannelCatalogAuthorityService.class
    })
    RetailSelectionService retailSelectionService(
            RetailSelectionMapper selections,
            RetailProductSourcePort products,
            @Lazy ChannelCatalogAuthorityService authorities,
            ObjectProvider<Clock> clocks) {
        return new RetailSelectionServiceImpl(
                selections, products, authorities, clocks.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnBean({
        RetailSelectionMapper.class,
        RetailSelectionService.class,
        DataSource.class,
        PlatformTransactionManager.class,
        ObjectMapper.class
    })
    RetailSelectionManagementService retailSelectionManagementService(
            DataSource dataSource,
            @Qualifier("transactionManager") PlatformTransactionManager manager,
            ObjectMapper json,
            RetailSelectionMapper selections,
            RetailSelectionService publication) {
        return new RetailSelectionManagementServiceImpl(
                new JdbcTemplate(dataSource), manager, json, selections, publication);
    }

    @Bean
    @ConditionalOnBean({StoreCardQueryPort.class, CatalogPriceSourcePort.class, MarketPort.class})
    OriginalRetailCatalogAvailability originalRetailCatalogAvailability(
            StoreCardQueryPort stores,
            CatalogPriceSourcePort prices,
            MarketPort markets,
            ObjectProvider<Clock> clocks) {
        return new OriginalRetailCatalogAvailability(
                stores, prices, markets, clocks.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnProperty(name = "shop.retail.commerce.catalog.enabled", havingValue = "true")
    @ConditionalOnBean({
        RetailSelectionMapper.class,
        RetailSourceValidationProviderImpl.class,
        ChannelCatalogAuthorityService.class,
        GoodsCatalogQueryPort.class,
        CatalogPriceSourcePort.class,
        MediaPublicSourcePort.class,
        RetailCatalogAvailabilityPort.class,
        ObjectMapper.class
    })
    RetailCatalogSourcePortImpl retailCatalogSourcePort(
            RetailSelectionMapper selections,
            RetailSourceValidationProviderImpl validation,
            @Lazy ChannelCatalogAuthorityService authorities,
            GoodsCatalogQueryPort products,
            CatalogPriceSourcePort prices,
            MediaPublicSourcePort media,
            RetailCatalogAvailabilityPort calendars,
            ObjectMapper json,
            ObjectProvider<Clock> clocks,
            ObjectProvider<ChannelSourceWithdrawalProvider> withdrawals,
            @Value("${shop.commerce.catalog.withdrawal.enabled:false}") boolean withdrawalEnabled) {
        var source =
                new RetailCatalogSourcePortImpl(
                        selections,
                        validation,
                        authorities,
                        products,
                        prices,
                        media,
                        calendars,
                        json,
                        clocks.getIfAvailable(Clock::systemUTC));
        source.withdrawalSupport(
                withdrawalEnabled,
                () -> {
                    var providers =
                            withdrawals
                                    .orderedStream()
                                    .filter(provider -> provider.owner() == Owner.RETAIL)
                                    .toList();
                    if (providers.size() > 1)
                        throw new IllegalStateException(
                                "Duplicate original RETAIL withdrawal owner");
                    return providers.isEmpty() ? null : providers.getFirst();
                });
        return source;
    }
}
