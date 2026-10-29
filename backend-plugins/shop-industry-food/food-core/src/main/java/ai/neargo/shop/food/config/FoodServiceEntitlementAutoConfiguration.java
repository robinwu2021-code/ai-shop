package ai.neargo.shop.food.config;

import ai.neargo.shop.food.mapper.FoodBusinessOperationsMapper;
import ai.neargo.shop.food.mapper.ServiceEntitlementMapper;
import ai.neargo.shop.food.mapper.ServiceFormMapper;
import ai.neargo.shop.food.service.CheckCartService;
import ai.neargo.shop.food.service.ConfigRevisionService;
import ai.neargo.shop.food.service.ServiceEntitlementService;
import ai.neargo.shop.food.service.ServiceSlotService;
import ai.neargo.shop.food.service.impl.ServiceEntitlementServiceImpl;
import ai.neargo.shop.spi.inventory.MaterialCatalogQueryPort;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort;
import ai.neargo.shop.spi.trade.PricingCheckoutPort;
import ai.neargo.shop.spi.user.StoreCardQueryPort;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

import javax.sql.DataSource;

/**
 * Buffet quota and banquet planning are opt-in and require real admission, menu, material, market,
 * quote and capacity owners.
 */
@AutoConfiguration(after = FoodBusinessOperationsConfiguration.class)
@ConditionalOnProperty(
        name = {"shop.food.enabled", "shop.food.service-entitlements.enabled"},
        havingValue = "true")
public class FoodServiceEntitlementAutoConfiguration {
    @Bean
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean(ServiceEntitlementMapper.class)
    ServiceEntitlementMapper serviceEntitlementMapper(DataSource source) {
        return new ServiceEntitlementMapper(source);
    }

    @Bean
    @ConditionalOnBean({
        ServiceEntitlementMapper.class,
        ServiceFormMapper.class,
        FoodBusinessOperationsMapper.class,
        ConfigRevisionService.class,
        MaterialCatalogQueryPort.class,
        CatalogPriceSourcePort.class,
        PricingCheckoutPort.class,
        CheckCartService.class,
        ServiceSlotService.class,
        StoreCardQueryPort.class,
        ObjectMapper.class
    })
    @ConditionalOnMissingBean(ServiceEntitlementService.class)
    ServiceEntitlementServiceImpl serviceEntitlementService(
            ServiceEntitlementMapper mapper,
            ServiceFormMapper forms,
            FoodBusinessOperationsMapper operations,
            @Qualifier("transactionManager") PlatformTransactionManager manager,
            ConfigRevisionService revisions,
            MaterialCatalogQueryPort materials,
            CatalogPriceSourcePort markets,
            PricingCheckoutPort quotes,
            CheckCartService carts,
            ServiceSlotService slots,
            StoreCardQueryPort stores,
            ObjectMapper json,
            ObjectProvider<Clock> clocks) {
        return new ServiceEntitlementServiceImpl(
                mapper,
                forms,
                operations,
                manager,
                revisions,
                materials,
                markets,
                quotes,
                carts,
                slots,
                stores,
                json,
                clocks.getIfAvailable(Clock::systemUTC));
    }
}
