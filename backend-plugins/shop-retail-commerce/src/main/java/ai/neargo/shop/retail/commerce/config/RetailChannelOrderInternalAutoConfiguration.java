package ai.neargo.shop.retail.commerce.config;

import ai.neargo.shop.retail.commerce.api.internal.InternalRetailChannelOrderController;
import ai.neargo.shop.retail.commerce.api.internal.RetailChannelOrderInternalSecurity;
import ai.neargo.shop.retail.commerce.service.RetailChannelOrderService;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

/**
 * Signed private routes are explicit and require the actual embedded retail owner, never a remote
 * proxy to this process.
 */
@AutoConfiguration(after = RetailChannelOrderAutoConfiguration.class)
@ConditionalOnProperty(
        name = {
            "shop.retail.commerce.enabled",
            "shop.retail.commerce.orders.enabled",
            "shop.retail.commerce.internal.enabled"
        },
        havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBean({RetailChannelOrderService.class, ObjectMapper.class})
public class RetailChannelOrderInternalAutoConfiguration {
    @Bean
    RetailChannelOrderInternalSecurity retailChannelOrderInternalSecurity(
            @Value("${shop.services.internal-token:}") String token,
            @Value("${shop.retail.scope-key:}") String key,
            @Value("${shop.retail.scope-previous-key:}") String previous,
            ObjectProvider<Clock> clocks) {
        return new RetailChannelOrderInternalSecurity(
                token, key, previous, clocks.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    InternalRetailChannelOrderController internalRetailChannelOrderController(
            RetailChannelOrderInternalSecurity security,
            RetailChannelOrderService orders,
            ObjectMapper json) {
        return new InternalRetailChannelOrderController(security, orders, json);
    }
}
