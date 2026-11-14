package ai.neargo.shop.food.config;

import ai.neargo.common.data.scope.DataScopeRegistrar;
import ai.neargo.shop.auth.ScopeDim;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.food.mapper.DiningDeliveryMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CheckCartMapper;
import ai.neargo.shop.food.service.CheckCartService;
import ai.neargo.shop.food.service.DiningDeliveryService;
import ai.neargo.shop.food.service.impl.DiningDeliveryServiceImpl;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.Map;

/** Binds original cart CAS and address source rows to the same primary transaction. */
@AutoConfiguration(afterName = "ai.neargo.shop.food.config.FoodAutoConfiguration")
@ConditionalOnFoodEnabled
@Conditional(FoodPersistenceConfiguration.PersistenceEnabled.class)
public class DiningDeliveryAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(DiningDeliveryService.class)
    DiningDeliveryService diningDeliveryService(
            CheckCartService carts,
            CheckCartMapper cartRows,
            DiningDeliveryMapper addresses,
            @Qualifier("foodOutboxPublisher") OutboxPublisher outbox,
            ObjectMapper json,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        return new DiningDeliveryServiceImpl(
                carts, cartRows, addresses, outbox, json, manager, Clock.systemUTC());
    }

    @Bean
    DataScopeRegistrar diningDeliveryDataScope() {
        return registry ->
                registry.register("fnb_cart_delivery", Map.of(ScopeDim.MERCHANT, "entity_no"));
    }
}
