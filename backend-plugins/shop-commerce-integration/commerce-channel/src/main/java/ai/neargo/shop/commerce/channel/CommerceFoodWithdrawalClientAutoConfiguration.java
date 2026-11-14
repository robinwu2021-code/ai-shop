package ai.neargo.shop.commerce.channel;

import ai.neargo.shop.spi.commerce.ChannelSourceWithdrawalInternalApi;
import ai.neargo.shop.svc.ConfigServiceLocator;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Lazy;

import java.time.Clock;
import java.time.Duration;

@AutoConfiguration(
        beforeName = "ai.neargo.shop.commerce.config.CommerceCatalogWithdrawalAutoConfiguration")
@ConditionalOnProperty(
        name = {
            "shop.commerce.food-source-client.enabled",
            "shop.commerce.catalog.withdrawal.enabled"
        },
        havingValue = "true")
@EnableConfigurationProperties(ConfigServiceLocator.class)
@Import(InternalHttp.class)
public class CommerceFoodWithdrawalClientAutoConfiguration {
    @Bean
    @Lazy
    ChannelSourceWithdrawalInternalApi foodSourceWithdrawalInternalApi(
            InternalHttp http, @Value("${shop.food.scope-key:}") String key) {
        return http.client(
                ServiceName.FOOD,
                ChannelSourceWithdrawalInternalApi.class,
                Duration.ofSeconds(5),
                new CatalogWorkerScopeInterceptor(key, Clock.systemUTC()));
    }

    @Bean
    RemoteFoodSourceWithdrawalProvider remoteFoodSourceWithdrawalProvider(
            ObjectProvider<ChannelSourceWithdrawalInternalApi> api) {
        return new RemoteFoodSourceWithdrawalProvider(api::getObject, Clock.systemUTC());
    }
}
