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
import ai.neargo.inventory.client.InventoryReservationClient;
import ai.neargo.shop.food.channel.port.InventoryFoodStockProjectionPortImpl;
import ai.neargo.shop.spi.inventory.InventoryFoodStockProjectionPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import java.util.Optional;
import tools.jackson.databind.ObjectMapper;
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
    @Bean @ConditionalOnMissingBean(InventoryReservationClient.class)
    @ConditionalOnProperty(prefix="shop.inventory.service",name="base-url")
    InventoryReservationClient foodInventoryClient(Environment env){
        return InventoryReservationClient.create(()->Optional.ofNullable(env.getProperty("shop.inventory.service.base-url")),
                env.getProperty("shop.inventory.service.app-key"),env.getProperty("shop.inventory.service.app-secret"),
                Duration.ofMillis(env.getProperty("shop.inventory.service.connect-timeout-ms",Long.class,500L)),
                Duration.ofMillis(env.getProperty("shop.inventory.service.read-timeout-ms",Long.class,2000L)),
                env.getProperty("shop.inventory.service.breaker-threshold",Integer.class,5),
                Duration.ofSeconds(env.getProperty("shop.inventory.service.breaker-open-seconds",Long.class,10L)));
    }
    @Bean @ConditionalOnMissingBean(InventoryFoodStockProjectionPort.class)
    InventoryFoodStockProjectionPort foodInventoryProjectionPort(ObjectProvider<InventoryReservationClient> clients,ObjectMapper json){
        return new InventoryFoodStockProjectionPortImpl(clients::getIfAvailable,json);
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
