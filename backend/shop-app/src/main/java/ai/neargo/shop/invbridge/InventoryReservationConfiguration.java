package ai.neargo.shop.invbridge;

import ai.neargo.inventory.client.InventoryClient;
import ai.neargo.inventory.client.InventoryReservationClient;
import ai.neargo.shop.invbridge.port.InventoryReservationPortImpl;
import ai.neargo.shop.invbridge.port.RoutingInventoryReservationPort;
import ai.neargo.shop.invbridge.port.ServiceInventoryReservationPort;
import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.inventory.domain.service.InventoryAclService;
import ai.neargo.inventory.domain.service.ReservationService;
import ai.neargo.shop.spi.inventory.InventoryReservationPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


/**
 * This provider is available only while the existing inventory domain is the configured stock authority.
 *
 * <p>切流（ADR-046 M8）：端口按商家路由 —— 写入方已翻到库存服务的商家经客户端，其余照旧内嵌。
 * 客户端只在配了 {@code shop.inventory.service.base-url} 时才装；没配 = 一个商家都没切。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnInventory
public class InventoryReservationConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "shop.inventory", name = "stock-authority", havingValue = "INVENTORY")
    InventoryReservationPort inventoryReservationPort(InventoryAclService acl, ReservationService reservations,
                                                      ObjectProvider<InventoryReservationClient> client) {
        InventoryReservationClient remote = client.getIfAvailable();
        return new RoutingInventoryReservationPort(acl, new InventoryReservationPortImpl(acl, reservations),
                remote == null ? null : new ServiceInventoryReservationPort(remote));
    }

    @Bean
    @ConditionalOnProperty(prefix = "shop.inventory.service", name = "base-url")
    InventoryReservationClient inventoryReservationClient(InventoryClient client) {
        return client.reservations();
    }
}
