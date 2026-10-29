package ai.neargo.shop.food.config;

import ai.neargo.shop.food.mapper.BottleCustodyMappers.CustodyMapper;
import ai.neargo.shop.food.mapper.BottleCustodyMappers.MovementMapper;
import ai.neargo.shop.food.service.BottleCustodyService;
import ai.neargo.shop.food.service.impl.BottleCustodyServiceImpl;
import ai.neargo.shop.spi.inventory.MaterialCatalogQueryPort;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort;
import ai.neargo.shop.spi.user.CustomerAccountQueryPort;
import ai.neargo.shop.spi.user.StoreCardQueryPort;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

/**
 * Customer property custody is explicit and requires real primary persistence and original owner
 * ports.
 */
@AutoConfiguration(after = FoodAutoConfiguration.class)
@ConditionalOnFoodEnabled
@Conditional(FoodPersistenceConfiguration.PersistenceEnabled.class)
@ConditionalOnProperty(name = "shop.food.custody.enabled", havingValue = "true")
public class BottleCustodyAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(BottleCustodyService.class)
    BottleCustodyService bottleCustodyService(
            CustodyMapper custodies,
            MovementMapper movements,
            StoreCardQueryPort stores,
            CustomerAccountQueryPort customers,
            GoodsCatalogQueryPort goods,
            MaterialCatalogQueryPort materials,
            ObjectMapper json,
            ObjectProvider<Clock> clocks,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        return new BottleCustodyServiceImpl(
                custodies,
                movements,
                stores,
                customers,
                goods,
                materials,
                json,
                clocks.getIfAvailable(Clock::systemUTC),
                manager);
    }
}
