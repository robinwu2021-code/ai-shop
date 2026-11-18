package ai.neargo.shop.payclient;

import ai.neargo.shop.pay.entitlement.port.EntitlementCatalogPortImpl;
import ai.neargo.shop.pay.entitlement.policy.PayEntitlementFeatureGate;
import ai.neargo.shop.pay.entitlement.service.EntitlementCatalogService;
import ai.neargo.shop.spi.entitlement.EntitlementCatalogPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "shop.pay.deployment", havingValue = "embedded", matchIfMissing = true)
public class EntitlementCatalogRouting {
    @Bean
    EntitlementCatalogPort entitlementCatalogPort(EntitlementCatalogService catalog,
                                                PayEntitlementFeatureGate features) {
        return new EntitlementCatalogPortImpl(catalog, features);
    }
}
