package ai.neargo.shop.food.channel.config;

import ai.neargo.shop.food.channel.port.CoreInventoryReservationPortImpl;
import ai.neargo.shop.food.channel.port.CoreInventoryProductionPortImpl;
import ai.neargo.shop.spi.inventory.InventoryProductionPort;
import ai.neargo.shop.spi.inventory.InventoryProductionInternalApi;
import ai.neargo.shop.spi.inventory.InventoryReservationInternalApi;
import ai.neargo.shop.spi.inventory.InventoryReservationPort;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanInternalApi;
import ai.neargo.shop.food.channel.port.CoreInventoryDemandPlanPortImpl;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/** Standalone food-server import only; embedded PLATFORM retains its authoritative local inventory port. */
@Configuration(proxyBeanMethods = false)
public class FoodInventoryClientConfiguration {
    @Bean @Lazy
    ai.neargo.shop.spi.food.FoodProductionStockInternalApi foodProductionStockInternalApi(InternalHttp http) {
        return http.client(ServiceName.PLATFORM,ai.neargo.shop.spi.food.FoodProductionStockInternalApi.class,Duration.ofSeconds(5));
    }
    @Bean @ConditionalOnMissingBean(ai.neargo.shop.spi.food.FoodProductionStockPort.class)
    ai.neargo.shop.spi.food.FoodProductionStockPort coreFoodProductionStockPort(
            ObjectProvider<ai.neargo.shop.spi.food.FoodProductionStockInternalApi> api) {
        return new ai.neargo.shop.food.channel.port.CoreFoodProductionStockPortImpl(api::getObject);
    }
    @Bean @Lazy
    ai.neargo.shop.spi.inventory.InventoryFoodStockProjectionInternalApi inventoryFoodStockProjectionInternalApi(InternalHttp http) {
        return http.client(ServiceName.PLATFORM,ai.neargo.shop.spi.inventory.InventoryFoodStockProjectionInternalApi.class,Duration.ofSeconds(5));
    }
    @Bean @ConditionalOnMissingBean(ai.neargo.shop.spi.inventory.InventoryFoodStockProjectionPort.class)
    ai.neargo.shop.spi.inventory.InventoryFoodStockProjectionPort coreFoodStockProjectionPort(
            ObjectProvider<ai.neargo.shop.spi.inventory.InventoryFoodStockProjectionInternalApi> api) {
        return new ai.neargo.shop.food.channel.port.CoreFoodStockProjectionPortImpl(api::getObject);
    }
    @Bean @Lazy
    InventoryDemandPlanInternalApi inventoryDemandPlanInternalApi(InternalHttp http) {
        return http.client(ServiceName.PLATFORM, InventoryDemandPlanInternalApi.class, Duration.ofSeconds(5));
    }
    @Bean @ConditionalOnMissingBean(InventoryDemandPlanPort.class)
    InventoryDemandPlanPort coreInventoryDemandPlanPort(ObjectProvider<InventoryDemandPlanInternalApi> api) {
        return new CoreInventoryDemandPlanPortImpl(api::getObject);
    }
    @Bean @Lazy
    InventoryProductionInternalApi inventoryProductionInternalApi(InternalHttp http) {
        return http.client(ServiceName.PLATFORM,InventoryProductionInternalApi.class,Duration.ofSeconds(5));
    }
    @Bean @ConditionalOnMissingBean(InventoryProductionPort.class)
    InventoryProductionPort coreInventoryProductionPort(ObjectProvider<InventoryProductionInternalApi> api) {
        return new CoreInventoryProductionPortImpl(api::getObject);
    }

    @Bean @Lazy
    InventoryReservationInternalApi inventoryReservationInternalApi(InternalHttp http) {
        return http.client(ServiceName.PLATFORM, InventoryReservationInternalApi.class, Duration.ofSeconds(5));
    }
    @Bean @ConditionalOnMissingBean(InventoryReservationPort.class)
    InventoryReservationPort coreInventoryReservationPort(ObjectProvider<InventoryReservationInternalApi> api) {
        return new CoreInventoryReservationPortImpl(api::getObject);
    }
}
