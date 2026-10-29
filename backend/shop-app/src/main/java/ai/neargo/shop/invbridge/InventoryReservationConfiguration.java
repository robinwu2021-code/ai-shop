package ai.neargo.shop.invbridge;

import ai.neargo.shop.invbridge.port.InventoryReservationPortImpl;
import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.inventory.domain.service.InventoryAclService;
import ai.neargo.inventory.domain.service.ReservationService;
import ai.neargo.shop.spi.inventory.InventoryReservationPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** This provider is available only while the existing inventory domain is the configured stock authority. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnInventory
public class InventoryReservationConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "shop.inventory", name = "stock-authority", havingValue = "INVENTORY")
    InventoryReservationPort inventoryReservationPort(InventoryAclService acl, ReservationService reservations) {
        return new InventoryReservationPortImpl(acl, reservations);
    }
}
