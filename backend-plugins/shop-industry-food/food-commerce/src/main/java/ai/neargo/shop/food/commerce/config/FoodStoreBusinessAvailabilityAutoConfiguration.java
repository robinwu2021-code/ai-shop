package ai.neargo.shop.food.commerce.config;

import ai.neargo.shop.food.commerce.port.FoodStoreBusinessAvailabilitySource;
import ai.neargo.shop.food.service.StoreCalendarSourceService;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

/**
 * The original calendar owner is required; no empty/default calendar is registered as an
 * availability source.
 */
@AutoConfiguration(
        afterName = {
            "ai.neargo.shop.food.config.FoodAutoConfiguration",
            "ai.neargo.shop.food.config.FoodWebAutoConfiguration"
        })
@ConditionalOnProperty(
        name = {"shop.food.enabled", "shop.food.commerce.store-availability.enabled"},
        havingValue = "true")
public class FoodStoreBusinessAvailabilityAutoConfiguration {
    @Bean
    @ConditionalOnBean(StoreCalendarSourceService.class)
    @ConditionalOnMissingBean(FoodStoreBusinessAvailabilitySource.class)
    FoodStoreBusinessAvailabilitySource foodStoreBusinessAvailabilitySource(
            StoreCalendarSourceService calendars, ObjectProvider<Clock> clocks) {
        return new FoodStoreBusinessAvailabilitySource(
                calendars, clocks.getIfAvailable(Clock::systemUTC));
    }
}
