package ai.neargo.shop.commerce.channel;

import ai.neargo.shop.spi.commerce.ChannelOrderSourceEvidenceInternalApi;
import ai.neargo.shop.spi.commerce.ChannelOrderSourceEvidencePort;
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

import java.time.Clock;
import java.time.Duration;

/**
 * Explicit standalone evidence access never installs a second CIM owner or replaces an embedded
 * original owner.
 */
@AutoConfiguration(afterName = "ai.neargo.shop.commerce.config.CommerceCoreAutoConfiguration")
@ConditionalOnProperty(name = "shop.commerce.order-evidence-client.enabled", havingValue = "true")
@ConditionalOnMissingBean(ChannelOrderSourceEvidencePort.class)
@EnableConfigurationProperties(ConfigServiceLocator.class)
@Import(InternalHttp.class)
public class CommerceOrderEvidenceClientAutoConfiguration {
    @Bean
    @Lazy
    ChannelOrderSourceEvidenceInternalApi channelOrderSourceEvidenceInternalApi(InternalHttp http) {
        return http.client(
                ServiceName.COMMERCE,
                ChannelOrderSourceEvidenceInternalApi.class,
                Duration.ofSeconds(5));
    }

    @Bean
    ChannelOrderSourceEvidencePort remoteChannelOrderSourceEvidencePort(
            ObjectProvider<ChannelOrderSourceEvidenceInternalApi> api) {
        return new RemoteChannelOrderSourceEvidencePort(api::getObject, Clock.systemUTC());
    }
}
