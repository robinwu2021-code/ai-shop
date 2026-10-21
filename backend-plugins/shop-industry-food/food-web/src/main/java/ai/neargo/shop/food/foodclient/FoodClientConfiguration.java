package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration.PolicyEnabled;
import ai.neargo.shop.food.service.CheckService;
import ai.neargo.shop.food.service.DishService;
import ai.neargo.shop.food.service.OptionGroupService;
import ai.neargo.shop.food.service.ModifierService;
import ai.neargo.shop.food.service.BundleService;
import ai.neargo.shop.food.spi.BundleInternalApi;
import ai.neargo.shop.food.service.ConfigRevisionService;
import ai.neargo.shop.food.spi.ConfigRevisionInternalApi;
import ai.neargo.shop.food.spi.ModifierInternalApi;
import ai.neargo.shop.food.spi.OptionGroupInternalApi;
import ai.neargo.shop.food.spi.DishInternalApi;
import ai.neargo.shop.food.service.DiningSessionService;
import ai.neargo.shop.food.service.ReservationService;
import ai.neargo.shop.food.service.StoreOperationService;
import ai.neargo.shop.food.service.TableService;
import ai.neargo.shop.food.spi.CheckInternalApi;
import ai.neargo.shop.food.spi.DiningSessionInternalApi;
import ai.neargo.shop.food.spi.ReservationInternalApi;
import ai.neargo.shop.food.spi.ServiceCalendarInternalApi;
import ai.neargo.shop.food.spi.StoreOperationInternalApi;
import ai.neargo.shop.food.spi.StorePolicyPublicationInternalApi;
import ai.neargo.shop.food.spi.TableInternalApi;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Only the standalone gateway uses a remote Service; embedded assembly retains the local implementation. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnFoodWebEnabled
@ConditionalOnProperty(prefix = "shop.food", name = "deployment", havingValue = "standalone")
@ConditionalOnBean(InternalHttp.class)
@Conditional(PolicyEnabled.class)
@Profile({"api", "ops"})
public class FoodClientConfiguration {
    @Bean
    @ConditionalOnMissingBean(ConfigRevisionService.class)
    ConfigRevisionService remoteConfigRevisionService(InternalHttp http) {
        return new RemoteConfigRevisionService(http.client(ServiceName.FOOD, ConfigRevisionInternalApi.class,
                Duration.ofSeconds(5)));
    }

    @Bean
    @ConditionalOnMissingBean(BundleService.class)
    BundleService remoteBundleService(InternalHttp http) {
        return new RemoteBundleService(http.client(ServiceName.FOOD, BundleInternalApi.class,
                Duration.ofSeconds(5)));
    }

    @Bean
    @ConditionalOnMissingBean(ModifierService.class)
    ModifierService remoteModifierService(InternalHttp http) {
        return new RemoteModifierService(http.client(ServiceName.FOOD, ModifierInternalApi.class,
                Duration.ofSeconds(5)));
    }

    @Bean
    @ConditionalOnMissingBean(OptionGroupService.class)
    OptionGroupService remoteOptionGroupService(InternalHttp http) {
        return new RemoteOptionGroupService(http.client(ServiceName.FOOD, OptionGroupInternalApi.class,
                Duration.ofSeconds(5)));
    }

    @Bean
    @ConditionalOnMissingBean(DishService.class)
    DishService remoteDishService(InternalHttp http) {
        return new RemoteDishService(http.client(ServiceName.FOOD, DishInternalApi.class, Duration.ofSeconds(5)));
    }

    @Bean
    @ConditionalOnMissingBean(DiningSessionService.class)
    DiningSessionService remoteDiningSessionService(InternalHttp http) {
        return new RemoteDiningSessionService(http.client(ServiceName.FOOD,
                DiningSessionInternalApi.class, Duration.ofSeconds(5)));
    }

    @Bean
    @ConditionalOnMissingBean(CheckService.class)
    CheckService remoteCheckService(InternalHttp http) {
        return new RemoteCheckService(http.client(ServiceName.FOOD,
                CheckInternalApi.class, Duration.ofSeconds(5)));
    }

    @Bean
    @ConditionalOnMissingBean(ReservationService.class)
    ReservationService remoteReservationService(InternalHttp http) {
        return new RemoteReservationService(http.client(ServiceName.FOOD,
                ReservationInternalApi.class, Duration.ofSeconds(5)));
    }

    @Bean
    @ConditionalOnMissingBean(TableService.class)
    TableService remoteTableService(InternalHttp http) {
        return new RemoteTableService(http.client(ServiceName.FOOD, TableInternalApi.class, Duration.ofSeconds(5)));
    }

    @Bean
    @ConditionalOnMissingBean(StoreOperationService.class)
    StoreOperationService remoteStoreOperationService(InternalHttp http) {
        return new RemoteStoreOperationService(http.client(ServiceName.FOOD, StoreOperationInternalApi.class,
                Duration.ofSeconds(5)),
                http.client(ServiceName.FOOD, StorePolicyPublicationInternalApi.class, Duration.ofSeconds(5)),
                http.client(ServiceName.FOOD, ServiceCalendarInternalApi.class, Duration.ofSeconds(5)));
    }
}
