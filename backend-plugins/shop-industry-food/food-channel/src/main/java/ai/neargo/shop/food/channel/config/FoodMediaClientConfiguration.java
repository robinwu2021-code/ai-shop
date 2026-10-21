package ai.neargo.shop.food.channel.config;

import ai.neargo.shop.food.channel.port.CoreMediaAssetQueryPortImpl;
import ai.neargo.shop.spi.media.MediaAssetInternalApi;
import ai.neargo.shop.spi.media.MediaAssetQueryPort;
import ai.neargo.shop.svc.ConfigServiceLocator;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Lazy;

/** Standalone-only import; embedded hosts keep the local original asset authority. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix="shop.food", name="enabled", havingValue="true")
@EnableConfigurationProperties(ConfigServiceLocator.class)
@Import(InternalHttp.class)
public class FoodMediaClientConfiguration {
    @Bean
    @Lazy
    MediaAssetInternalApi mediaAssetInternalApi(InternalHttp http) {
        return http.client(ServiceName.PLATFORM, MediaAssetInternalApi.class, Duration.ofSeconds(5));
    }

    @Bean
    MediaAssetQueryPort coreMediaAssetQueryPort(ObjectProvider<MediaAssetInternalApi> api) {
        return new CoreMediaAssetQueryPortImpl(api::getObject);
    }
}
