package ai.neargo.shop.food.channel.config;

import ai.neargo.shop.food.channel.port.CoreGoodsCatalogQueryPortImpl;
import ai.neargo.shop.food.channel.port.CoreSpecValueCatalogQueryPortImpl;
import ai.neargo.shop.spi.product.SpecValueCatalogInternalApi;
import ai.neargo.shop.spi.product.SpecValueCatalogQueryPort;
import ai.neargo.shop.spi.product.GoodsCatalogInternalApi;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort;
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

/** Explicitly imported by food-server only; embedded platform keeps its local product owner. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "shop.food", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(ConfigServiceLocator.class)
@Import(InternalHttp.class)
public class FoodCatalogClientConfiguration {
    @Bean
    @Lazy
    SpecValueCatalogInternalApi specValueCatalogInternalApi(InternalHttp http) {
        return http.client(ServiceName.PLATFORM, SpecValueCatalogInternalApi.class, Duration.ofSeconds(5));
    }

    @Bean
    SpecValueCatalogQueryPort coreSpecValueCatalogQueryPort(ObjectProvider<SpecValueCatalogInternalApi> api) {
        return new CoreSpecValueCatalogQueryPortImpl(api::getObject);
    }

    @Bean
    @Lazy
    GoodsCatalogInternalApi goodsCatalogInternalApi(InternalHttp http) {
        return http.client(ServiceName.PLATFORM, GoodsCatalogInternalApi.class, Duration.ofSeconds(5));
    }

    @Bean
    GoodsCatalogQueryPort coreGoodsCatalogQueryPort(ObjectProvider<GoodsCatalogInternalApi> api) {
        return new CoreGoodsCatalogQueryPortImpl(api::getObject);
    }
}
