package ai.neargo.shop.commerce.channel;

import ai.neargo.shop.commerce.service.SourceNoticeService;
import ai.neargo.shop.spi.commerce.SourceNoticeInternalApi;
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
 * Embedded persistence wins; an independent event producer uses one explicit COMMERCE destination.
 */
@AutoConfiguration(afterName = "ai.neargo.shop.commerce.config.CommerceCoreAutoConfiguration")
@ConditionalOnProperty(name = "shop.commerce.notice-client.enabled", havingValue = "true")
@ConditionalOnMissingBean(SourceNoticeService.class)
@EnableConfigurationProperties(ConfigServiceLocator.class)
@Import(InternalHttp.class)
public class CommerceNoticeClientAutoConfiguration {
    @Bean
    @Lazy
    SourceNoticeInternalApi sourceNoticeInternalApi(InternalHttp http) {
        return http.client(
                ServiceName.COMMERCE, SourceNoticeInternalApi.class, Duration.ofSeconds(5));
    }

    @Bean
    SourceNoticeService remoteSourceNoticeService(ObjectProvider<SourceNoticeInternalApi> api) {
        return new RemoteSourceNoticeService(api::getObject);
    }
}
