package ai.neargo.shop.food.config;

import ai.neargo.common.data.scope.DataScopeRegistrar;
import ai.neargo.shop.auth.ScopeDim;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.food.mapper.DiningIdentityMappers.AuthorizationMapper;
import ai.neargo.shop.food.mapper.DiningIdentityMappers.DeviceMapper;
import ai.neargo.shop.food.mapper.DiningIdentityMappers.OperationMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CheckMapper;
import ai.neargo.shop.food.mapper.FoodMappers.GuestSessionMapper;
import ai.neargo.shop.food.service.DiningIdentityService;
import ai.neargo.shop.food.service.impl.DiningIdentityServiceImpl;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.Map;

/**
 * Commits device grants and original guest-session facts through the same FOOD primary transaction.
 */
@AutoConfiguration(afterName = "ai.neargo.shop.food.config.FoodAutoConfiguration")
@ConditionalOnFoodEnabled
@Conditional(FoodPersistenceConfiguration.PersistenceEnabled.class)
public class DiningIdentityAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(DiningIdentityService.class)
    DiningIdentityService diningIdentityService(
            DeviceMapper devices,
            AuthorizationMapper authorizations,
            OperationMapper operations,
            GuestSessionMapper sessions,
            CheckMapper checks,
            @Qualifier("foodOutboxPublisher") OutboxPublisher outbox,
            @Qualifier("transactionManager") PlatformTransactionManager manager,
            ObjectMapper json) {
        return new DiningIdentityServiceImpl(
                devices,
                authorizations,
                operations,
                sessions,
                checks,
                outbox,
                manager,
                json,
                Clock.systemUTC());
    }

    @Bean
    DataScopeRegistrar diningIdentityDataScope() {
        return registry -> {
            registry.register("fnb_dining_device", Map.of(ScopeDim.MERCHANT, "entity_no"));
            registry.register("fnb_identity_authorization", Map.of(ScopeDim.MERCHANT, "entity_no"));
            registry.register("fnb_identity_operation", Map.of(ScopeDim.MERCHANT, "entity_no"));
        };
    }
}
