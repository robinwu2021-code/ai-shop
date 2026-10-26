package ai.neargo.shop.commerce.channel;

import ai.neargo.shop.spi.media.MediaPublicSourceInternalApi;
import ai.neargo.shop.spi.media.MediaPublicSourcePort;
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
        prefix = "shop.commerce.media-client",
        name = "enabled",
        havingValue = "true")
@ConditionalOnMissingBean(MediaPublicSourcePort.class)
@EnableConfigurationProperties(ConfigServiceLocator.class)
@Import(InternalHttp.class)
public class CommerceMediaClientAutoConfiguration {
    @Bean
    @Lazy
    MediaPublicSourceInternalApi mediaPublicSourceInternalApi(InternalHttp http) {
        return http.client(
                ServiceName.PLATFORM, MediaPublicSourceInternalApi.class, Duration.ofSeconds(5));
    }

    @Bean
    MediaPublicSourcePort remoteMediaPublicSourcePort(
            ObjectProvider<MediaPublicSourceInternalApi> api) {
        return new RemoteMediaPublicSourcePort(query -> api.getObject().capture(query));
    }
}
