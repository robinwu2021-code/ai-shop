package ai.neargo.shop.retail.commerce.config;

import ai.neargo.shop.retail.commerce.api.internal.InternalRetailCatalogSourceController;
import ai.neargo.shop.retail.commerce.port.RetailCatalogSourcePortImpl;
import ai.neargo.shop.retail.commerce.port.RetailSourceValidationProviderImpl;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

@AutoConfiguration(after = RetailCommerceAutoConfiguration.class)
@ConditionalOnProperty(name = "shop.retail.commerce.internal.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class RetailCommerceInternalAutoConfiguration {
    @Bean
    @ConditionalOnBean({RetailSourceValidationProviderImpl.class, ObjectMapper.class})
    InternalRetailCatalogSourceController internalRetailCatalogSourceController(
            @Value("${shop.services.internal-token:}") String token,
            RetailSourceValidationProviderImpl validation,
            ObjectProvider<RetailCatalogSourcePortImpl> catalog,
            ObjectMapper json,
            ObjectProvider<Clock> clocks) {
        return new InternalRetailCatalogSourceController(
                token, validation, catalog, json, clocks.getIfAvailable(Clock::systemUTC));
    }
}
