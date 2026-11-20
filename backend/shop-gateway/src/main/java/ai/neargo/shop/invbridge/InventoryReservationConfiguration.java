package ai.neargo.shop.invbridge;

import ai.neargo.inventory.client.InventoryClient;
import ai.neargo.inventory.client.InventoryReservationClient;
import ai.neargo.shop.gateway.core.proxy.GatewaySigning;
import ai.neargo.shop.invbridge.port.InventoryReservationPortImpl;
import ai.neargo.shop.invbridge.port.RoutingInventoryReservationPort;
import ai.neargo.shop.invbridge.port.ServiceInventoryReservationPort;
import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.inventory.domain.service.InventoryAclService;
import ai.neargo.inventory.domain.service.ReservationService;
import ai.neargo.shop.spi.inventory.InventoryReservationPort;
import ai.neargo.shop.svc.ServiceLocator;
import ai.neargo.shop.svc.ServiceName;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.AllNestedConditions;
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * This provider is available only while the existing inventory domain is the configured stock authority.
 *
 * <p>切流（ADR-046 M8）：端口按商家路由 —— 写入方已翻到库存服务的商家经客户端，其余照旧内嵌。
 * 配置库存服务发现或固定地址后装配客户端。
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

    /**
     * 商城访问独立库存服务的唯一客户端（TDD-进销存独立服务与多业态 §2.23）：系统凭证，按商家（账本）切换。
     * 预留、按单号的系统级动作、商家后台转发都从它派生，共用一个连接与熔断器。
     */
    @Bean
    @Conditional(InventoryEndpointConfigured.class)
    InventoryClient inventoryClient(Environment env, ServiceLocator locator, ObjectProvider<GatewaySigning> signing) {
        // 网关的签名上下文（谁、哪一端、哪家店、判过哪些功能点）随每个调用带给库存；库存那边只比对、不拒绝。没配密钥时不加头
        GatewaySigning gateway = signing.getIfAvailable();
        return InventoryClient.create(
                () -> locator.baseUrlOf(ServiceName.INVENTORY)
                        .or(() -> Optional.ofNullable(env.getProperty("shop.inventory.service.base-url"))),
                env.getProperty("shop.inventory.service.app-key"),
                env.getProperty("shop.inventory.service.app-secret"),
                Duration.ofMillis(env.getProperty("shop.inventory.service.connect-timeout-ms", Long.class, 500L)),
                // 下单路径：同机 RTT 1 ~ 3 ms，2 秒还没回就是服务出事了
                Duration.ofMillis(env.getProperty("shop.inventory.service.read-timeout-ms", Long.class, 2000L)),
                env.getProperty("shop.inventory.service.breaker-threshold", Integer.class, 5),
                Duration.ofSeconds(env.getProperty("shop.inventory.service.breaker-open-seconds", Long.class, 10L)),
                "shop.inventory.service",
                gateway == null ? List.of() : List.of(gateway.outbound(ServiceName.INVENTORY)));
    }

    @Bean
    @Conditional(InventoryEndpointConfigured.class)
    InventoryReservationClient inventoryReservationClient(InventoryClient client) {
        return client.reservations();
    }

    static class InventoryEndpointConfigured extends AnyNestedCondition {
        InventoryEndpointConfigured() {
            super(ConfigurationPhase.REGISTER_BEAN);
        }

        @ConditionalOnProperty(prefix = "shop.inventory.service", name = "base-url")
        static class FixedAddress {}

        @Conditional(DiscoveryEnabled.class)
        static class NacosDiscovery {}
    }

    static class DiscoveryEnabled extends AllNestedConditions {
        DiscoveryEnabled() {
            super(ConfigurationPhase.REGISTER_BEAN);
        }

        @ConditionalOnProperty(name = "shop.nacos.enabled", havingValue = "true")
        static class Enabled {}

        @ConditionalOnProperty(name = "shop.nacos.discovery.services.inventory")
        static class InventoryMapped {}
    }
}
