package ai.neargo.shop.food.config;

import ai.neargo.shop.food.mapper.OfflineDraftMapper;
import ai.neargo.shop.food.service.CheckCartService;
import ai.neargo.shop.food.service.DiningIdentityService;
import ai.neargo.shop.food.service.OfflineDraftService;
import ai.neargo.shop.food.service.impl.OfflineDraftServiceImpl;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

import javax.sql.DataSource;

/** Default off, original FOOD primary owner only; no independent offline commerce engine. */
@AutoConfiguration(after = {FoodAutoConfiguration.class, DiningIdentityAutoConfiguration.class})
@Conditional(FoodPersistenceConfiguration.PersistenceEnabled.class)
@ConditionalOnProperty(
        name = {"shop.food.enabled", "shop.food.offline-drafts.enabled"},
        havingValue = "true")
public class FoodOfflineDraftAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    OfflineDraftMapper offlineDraftMapper(DataSource source) {
        return new OfflineDraftMapper(source);
    }

    @Bean
    @ConditionalOnMissingBean(OfflineDraftService.class)
    @ConditionalOnBean({DiningIdentityService.class, CheckCartService.class, ObjectMapper.class})
    OfflineDraftService offlineDraftService(
            OfflineDraftMapper mapper,
            DiningIdentityService identities,
            CheckCartService carts,
            ObjectMapper json,
            ObjectProvider<Clock> clocks,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        return new OfflineDraftServiceImpl(
                mapper, identities, carts, json, clocks.getIfAvailable(Clock::systemUTC), manager);
    }
}
