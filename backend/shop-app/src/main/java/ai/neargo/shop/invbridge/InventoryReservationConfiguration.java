package ai.neargo.shop.invbridge;

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
import org.springframework.core.env.Environment;

import java.time.Duration;
import java.util.Optional;

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
    InventoryReservationClient inventoryReservationClient(Environment env) {
        return InventoryReservationClient.create(
                () -> Optional.ofNullable(env.getProperty("shop.inventory.service.base-url")),
                env.getProperty("shop.inventory.service.app-key"),
                env.getProperty("shop.inventory.service.app-secret"),
                Duration.ofMillis(env.getProperty("shop.inventory.service.connect-timeout-ms", Long.class, 500L)),
                // 下单路径：同机 RTT 1 ~ 3 ms，2 秒还没回就是服务出事了
                Duration.ofMillis(env.getProperty("shop.inventory.service.read-timeout-ms", Long.class, 2000L)),
                env.getProperty("shop.inventory.service.breaker-threshold", Integer.class, 5),
                Duration.ofSeconds(env.getProperty("shop.inventory.service.breaker-open-seconds", Long.class, 10L)));
    }
}
