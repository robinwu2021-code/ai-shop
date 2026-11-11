package ai.neargo.shop.invbridge;

import ai.neargo.inventory.client.InventoryClient;
import java.time.Duration;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Pure remote transport; no embedded ledger, mapper, writer switch or database is activated. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="shop.inventory.service",name="base-url")
public class InventoryClientConfiguration {
    /**
     * 商城访问独立库存服务的唯一客户端（TDD-进销存独立服务与多业态 §2.23）：系统凭证，按商家（账本）切换。
     * 预留、按单号的系统级动作、商家后台转发都从它派生，共用一个连接与熔断器。
     */
    @Bean
    @ConditionalOnMissingBean(InventoryClient.class)
    @ConditionalOnProperty(prefix = "shop.inventory.service", name = "base-url")
    InventoryClient inventoryClient(Environment env) {
        return InventoryClient.create(
                () -> Optional.ofNullable(env.getProperty("shop.inventory.service.base-url")),
                env.getProperty("shop.inventory.service.app-key"),
                env.getProperty("shop.inventory.service.app-secret"),
                Duration.ofMillis(env.getProperty("shop.inventory.service.connect-timeout-ms", Long.class, 500L)),
                // 下单路径：同机 RTT 1 ~ 3 ms，2 秒还没回就是服务出事了
                Duration.ofMillis(env.getProperty("shop.inventory.service.read-timeout-ms", Long.class, 2000L)),
                env.getProperty("shop.inventory.service.breaker-threshold", Integer.class, 5),
                Duration.ofSeconds(env.getProperty("shop.inventory.service.breaker-open-seconds", Long.class, 10L)),
                "shop.inventory.service");
    }

}
