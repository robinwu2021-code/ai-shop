package ai.neargo.shop.commerce.channel;

import ai.neargo.shop.commerce.service.ChannelCatalogAuthorityService;
import ai.neargo.shop.spi.commerce.ChannelCatalogAuthorityInternalApi;
import ai.neargo.shop.svc.ConfigServiceLocator;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Lazy;

import java.time.Duration;

/**
 * Explicit opt-in permits a source worker to read authority without installing the CIM database
 * owner.
 */
@AutoConfiguration(afterName = "ai.neargo.shop.commerce.config.CommerceCoreAutoConfiguration")
@ConditionalOnProperty(
        prefix = "shop.commerce.authority-client",
        name = "enabled",
        havingValue = "true")
@ConditionalOnMissingBean(ChannelCatalogAuthorityService.class)
@EnableConfigurationProperties(ConfigServiceLocator.class)
@Import(InternalHttp.class)
public class CommerceAuthorityClientAutoConfiguration {
    @Bean
    @Lazy
    ChannelCatalogAuthorityInternalApi channelCatalogAuthorityInternalApi(InternalHttp http) {
        return http.client(
                ServiceName.COMMERCE,
                ChannelCatalogAuthorityInternalApi.class,
                Duration.ofSeconds(5));
    }

    @Bean
    ChannelCatalogAuthorityService remoteChannelCatalogAuthorityService(
            ObjectProvider<ChannelCatalogAuthorityInternalApi> api) {
        return new RemoteChannelCatalogAuthorityService(query -> api.getObject().capture(query));
    }
}
