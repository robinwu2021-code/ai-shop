package ai.neargo.shop.food.channel.config;

import ai.neargo.shop.food.channel.port.CoreInventoryReservationPortImpl;
import ai.neargo.shop.spi.inventory.InventoryReservationInternalApi;
import ai.neargo.shop.spi.inventory.InventoryReservationPort;
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
    InventoryReservationInternalApi inventoryReservationInternalApi(InternalHttp http) {
        return http.client(ServiceName.PLATFORM, InventoryReservationInternalApi.class, Duration.ofSeconds(5));
    }
    @Bean @ConditionalOnMissingBean(InventoryReservationPort.class)
    InventoryReservationPort coreInventoryReservationPort(ObjectProvider<InventoryReservationInternalApi> api) {
        return new CoreInventoryReservationPortImpl(api::getObject);
    }
}
