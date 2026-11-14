package ai.neargo.shop.commerce.channel;

import ai.neargo.shop.spi.commerce.RetailCatalogSourceInternalApi;
import ai.neargo.shop.svc.ConfigServiceLocator;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Lazy;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;

/**
 * RETAIL transport targets its actual PLATFORM owner; duplicate local/remote owners fail in the
 * original registries.
 */
@AutoConfiguration(beforeName = "ai.neargo.shop.commerce.config.CommerceCoreAutoConfiguration")
@ConditionalOnProperty(name = "shop.commerce.retail-source-client.enabled", havingValue = "true")
@EnableConfigurationProperties(ConfigServiceLocator.class)
@Import(InternalHttp.class)
public class CommerceRetailSourceClientAutoConfiguration {
    @Bean
    @Lazy
    RetailCatalogSourceInternalApi retailCatalogSourceInternalApi(InternalHttp http) {
        return http.client(
                ServiceName.PLATFORM, RetailCatalogSourceInternalApi.class, Duration.ofSeconds(10));
    }

    @Bean
    RemoteRetailCatalogSourcePort remoteRetailCatalogSourcePort(
            ObjectProvider<RetailCatalogSourceInternalApi> api,
            ObjectMapper json,
            ObjectProvider<Clock> clocks) {
        return new RemoteRetailCatalogSourcePort(
                api::getObject, json, clocks.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    RemoteRetailSourceValidationProvider remoteRetailSourceValidationProvider(
            ObjectProvider<RetailCatalogSourceInternalApi> api, ObjectProvider<Clock> clocks) {
        return new RemoteRetailSourceValidationProvider(
                api::getObject, clocks.getIfAvailable(Clock::systemUTC));
    }
}
