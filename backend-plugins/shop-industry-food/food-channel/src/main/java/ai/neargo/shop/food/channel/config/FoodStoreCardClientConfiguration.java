package ai.neargo.shop.food.channel.config;

import ai.neargo.shop.food.channel.port.CoreStoreCardQueryPortImpl;
import ai.neargo.shop.spi.user.StoreCardInternalApi;
import ai.neargo.shop.spi.user.StoreCardQueryPort;
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

/** Standalone-only import keeps the embedded platform attached to its original store directory. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="shop.food", name="enabled", havingValue="true")
@EnableConfigurationProperties(ConfigServiceLocator.class)
@Import(InternalHttp.class)
public class FoodStoreCardClientConfiguration {
    @Bean
    @Lazy
    StoreCardInternalApi storeCardInternalApi(InternalHttp http) {
        return http.client(ServiceName.PLATFORM, StoreCardInternalApi.class, Duration.ofSeconds(5));
    }

    @Bean
    StoreCardQueryPort coreStoreCardQueryPort(ObjectProvider<StoreCardInternalApi> api) {
        return new CoreStoreCardQueryPortImpl(api::getObject);
    }
}
