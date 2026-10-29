package ai.neargo.shop.food.config;

import ai.neargo.shop.food.port.RemoteDiningKitchenReportFactsPortAdapter;
import ai.neargo.shop.food.port.RemoteDiningReportFactsPortAdapter;
import ai.neargo.shop.food.service.DiningKitchenReportFactsService;
import ai.neargo.shop.food.service.DiningReportFactsService;
import ai.neargo.shop.food.spi.DiningKitchenReportInternalApi;
import ai.neargo.shop.food.spi.DiningReportInternalApi;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

import java.time.Duration;

/** Facts/day/correction transport is required by standalone workers as well as API instances. */
@AutoConfiguration
@ConditionalOnFoodWebEnabled
@ConditionalOnProperty(name = "shop.food.deployment", havingValue = "standalone")
@ConditionalOnProperty(
        name = {"shop.report.enabled", "shop.report.food.enabled"},
        havingValue = "true")
@ConditionalOnBean(InternalHttp.class)
@Profile({"api", "ops", "worker"})
public class FoodReportFactsAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(DiningKitchenReportFactsService.class)
    DiningKitchenReportFactsService remoteDiningKitchenReportFactsService(InternalHttp http) {
        return new RemoteDiningKitchenReportFactsPortAdapter(
                http.client(
                        ServiceName.FOOD,
                        DiningKitchenReportInternalApi.class,
                        Duration.ofSeconds(15)));
    }

    @Bean
    @ConditionalOnMissingBean(DiningReportFactsService.class)
    DiningReportFactsService remoteDiningReportFactsService(InternalHttp http) {
        return new RemoteDiningReportFactsPortAdapter(
                http.client(
                        ServiceName.FOOD, DiningReportInternalApi.class, Duration.ofSeconds(15)));
    }
}
