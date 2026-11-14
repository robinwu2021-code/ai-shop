package ai.neargo.shop.food.channel.config;

import ai.neargo.shop.food.channel.port.CoreDishOfferQueryPortImpl;
import ai.neargo.shop.food.channel.port.CoreCatalogPriceSourcePortImpl;
import ai.neargo.shop.spi.product.CatalogPriceSourceInternalApi;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort;
import ai.neargo.shop.spi.product.DishOfferInternalApi;
import ai.neargo.shop.spi.product.DishOfferQueryPort;
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

/** Standalone-only import keeps the embedded platform attached to its original price and stock facts. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="shop.food", name="enabled", havingValue="true")
@EnableConfigurationProperties(ConfigServiceLocator.class)
@Import(InternalHttp.class)
public class FoodOfferClientConfiguration {
    @Bean
    @Lazy
    CatalogPriceSourceInternalApi catalogPriceSourceInternalApi(InternalHttp http) {
        return http.client(ServiceName.PLATFORM, CatalogPriceSourceInternalApi.class, Duration.ofSeconds(5));
    }
    @Bean
    CatalogPriceSourcePort coreCatalogPriceSourcePort(ObjectProvider<CatalogPriceSourceInternalApi> api) {
        return new CoreCatalogPriceSourcePortImpl(api::getObject);
    }

    @Bean
    @Lazy
    DishOfferInternalApi dishOfferInternalApi(InternalHttp http) {
        return http.client(ServiceName.PLATFORM, DishOfferInternalApi.class, Duration.ofSeconds(5));
    }

    @Bean
    DishOfferQueryPort coreDishOfferQueryPort(ObjectProvider<DishOfferInternalApi> api) {
        return new CoreDishOfferQueryPortImpl(api::getObject);
    }
}
