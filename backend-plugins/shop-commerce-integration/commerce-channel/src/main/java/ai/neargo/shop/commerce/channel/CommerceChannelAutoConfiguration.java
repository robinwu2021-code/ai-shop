package ai.neargo.shop.commerce.channel;

import ai.neargo.shop.spi.commerce.CommerceGateway;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.util.List;

/** 仅注册实际存在的适配器；没有注册的真实平台保持不可用。 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "shop.commerce", name = "enabled", havingValue = "true")
public class CommerceChannelAutoConfiguration {
    @Bean
    CommerceGatewayRegistry commerceGatewayRegistry(List<CommerceGateway> gateways) {
        return new CommerceGatewayRegistry(gateways);
    }
}
