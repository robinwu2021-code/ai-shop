package ai.neargo.shop.retail.commerce.config;

import ai.neargo.shop.retail.commerce.api.internal.InternalRetailStoreBusinessAvailabilityController;
import ai.neargo.shop.retail.commerce.api.internal.RetailChannelOrderInternalSecurity;
import ai.neargo.shop.retail.commerce.port.OriginalRetailCatalogAvailability;

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
 * Retail STORE source transport does not require retail order ingestion or fake selection
 * registration.
 */
@AutoConfiguration(after = RetailCommerceAutoConfiguration.class)
@ConditionalOnProperty(
        name = {"shop.retail.commerce.enabled", "shop.retail.commerce.internal.enabled"},
        havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class RetailStoreBusinessAvailabilityInternalAutoConfiguration {
    @Bean
    @ConditionalOnBean({OriginalRetailCatalogAvailability.class, ObjectMapper.class})
    InternalRetailStoreBusinessAvailabilityController
            internalRetailStoreBusinessAvailabilityController(
                    @Value("${shop.services.internal-token:}") String token,
                    @Value("${shop.retail.scope-key:}") String key,
                    @Value("${shop.retail.scope-previous-key:}") String previous,
                    OriginalRetailCatalogAvailability source,
                    ObjectMapper json,
                    ObjectProvider<Clock> clocks) {
        return new InternalRetailStoreBusinessAvailabilityController(
                new RetailChannelOrderInternalSecurity(
                        token, key, previous, clocks.getIfAvailable(Clock::systemUTC)),
                source,
                json);
    }
}
