package ai.neargo.shop.commerce.config;

import ai.neargo.shop.commerce.api.CommerceOperatorScope;
import ai.neargo.shop.commerce.api.ops.OpsCommerceController;
import ai.neargo.shop.spi.user.StoreDirectoryPort;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;

/**
 * OPS registration is independent from the merchant API profile and retains the original platform
 * permission/data scopes.
 */
@AutoConfiguration(afterName = "ai.neargo.shop.commerce.config.CommerceCoreAutoConfiguration")
@Profile("ops")
@ConditionalOnCommerceWebEnabled
@Import(OpsCommerceController.class)
public class CommerceOpsAutoConfiguration {
    @Bean
    CommerceOperatorScope commerceOperatorScope(StoreDirectoryPort stores) {
        return new CommerceOperatorScope(stores);
    }
}
