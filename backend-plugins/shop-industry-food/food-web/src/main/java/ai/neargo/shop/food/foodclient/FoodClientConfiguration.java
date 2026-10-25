package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.food.service.QueueService;
import ai.neargo.shop.food.service.DiningPickupVerificationService;
import ai.neargo.shop.food.spi.QueueInternalApi;
import ai.neargo.shop.food.spi.DiningPickupVerificationInternalApi;

import ai.neargo.shop.food.service.ProductionConsumptionService;
import ai.neargo.shop.food.service.KitchenReturnService;
import ai.neargo.shop.food.spi.ProductionConsumptionInternalApi;
import ai.neargo.shop.food.spi.KitchenReturnInternalApi;

import ai.neargo.shop.food.service.KitchenTaskService;
import ai.neargo.shop.food.service.BusinessCloseService;
import ai.neargo.shop.food.service.DiningReportService;
import ai.neargo.shop.food.service.DiningReportFactsService;
import ai.neargo.shop.food.spi.DiningReportInternalApi;
import ai.neargo.shop.food.service.FoodPrintService;
import ai.neargo.shop.food.spi.KitchenInternalApi;
import ai.neargo.shop.food.spi.BusinessCloseInternalApi;
import ai.neargo.shop.food.spi.FoodPrintInternalApi;


import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration.PolicyEnabled;
import ai.neargo.shop.food.service.CheckCartService;
import ai.neargo.shop.food.service.DiningPricingService;
import ai.neargo.shop.food.service.DiningOrderService;
import ai.neargo.shop.food.service.DiningReturnService;
import ai.neargo.shop.food.service.DiningCheckoutService;
import ai.neargo.shop.food.service.DiningTransactionRecoveryService;
import ai.neargo.shop.food.spi.DiningTransactionRecoveryInternalApi;
import ai.neargo.shop.food.spi.DiningCheckoutInternalApi;
import ai.neargo.shop.food.spi.DiningReturnInternalApi;
import ai.neargo.shop.food.spi.DiningOrderInternalApi;
import ai.neargo.shop.food.spi.DiningPricingInternalApi;
import ai.neargo.shop.food.service.DiningPricingFactsService;
import ai.neargo.shop.food.service.DiningOrderAuthorityService;
import ai.neargo.shop.food.service.BusinessReturnApprovalService;
import ai.neargo.shop.food.spi.BusinessReturnApprovalInternalApi;
import ai.neargo.shop.spi.trade.IndustryAmendmentSourcePort;
import ai.neargo.shop.spi.trade.IndustryAmendmentSourceInternalApi;
import ai.neargo.svc.client.ServiceCalls;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.spi.DiningOrderAuthorityInternalApi;
import ai.neargo.shop.food.spi.PricingSourceInternalApi;
import ai.neargo.shop.food.service.DiningBatchQueryService;
import ai.neargo.shop.food.spi.DiningBatchQueryInternalApi;
import ai.neargo.shop.food.service.BusinessShareService;
import ai.neargo.shop.food.service.BusinessReturnService;
import ai.neargo.shop.food.spi.BusinessReturnInternalApi;
import ai.neargo.shop.food.spi.BusinessShareInternalApi;
import ai.neargo.shop.food.service.CheckService;
import ai.neargo.shop.food.service.DishService;
import ai.neargo.shop.food.service.OptionGroupService;
import ai.neargo.shop.food.service.ModifierService;
import ai.neargo.shop.food.service.BundleService;
import ai.neargo.shop.food.service.RecipeService;
import ai.neargo.shop.food.service.MenuService;
import ai.neargo.shop.food.spi.CatalogInternalApi;
import ai.neargo.shop.food.service.CatalogService;
import ai.neargo.shop.food.spi.MenuChannelBindingInternalApi;
import ai.neargo.shop.food.service.MenuChannelBindingService;
import ai.neargo.shop.food.spi.StationInternalApi;
import ai.neargo.shop.food.spi.SalesRestrictionInternalApi;
import ai.neargo.shop.food.service.StationService;
import ai.neargo.shop.food.service.SalesRestrictionService;
import ai.neargo.shop.food.spi.MenuInternalApi;
import ai.neargo.shop.food.spi.RecipeInternalApi;
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
import ai.neargo.shop.food.spi.CartInternalApi;
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
    @Bean @ConditionalOnMissingBean(DiningReportService.class)
    DiningReportService remoteDiningReportService(InternalHttp http) {
        return new RemoteDiningReportService(http.client(ServiceName.FOOD,DiningReportInternalApi.class,Duration.ofSeconds(15)));
    }

    @Bean @ConditionalOnMissingBean(BusinessReturnApprovalService.class)
    BusinessReturnApprovalService remoteBusinessReturnApprovalService(InternalHttp http) {
        return new RemoteBusinessReturnApprovalService(http.client(ServiceName.FOOD,
                BusinessReturnApprovalInternalApi.class, Duration.ofSeconds(5)));
    }
    @Bean @ConditionalOnMissingBean(IndustryAmendmentSourcePort.class)
    IndustryAmendmentSourcePort remoteFoodAmendmentSource(InternalHttp http) {
        var api = http.client(ServiceName.FOOD, IndustryAmendmentSourceInternalApi.class, Duration.ofSeconds(5));
        return (reference, sourceReturnNo) -> {
            var result = ServiceCalls.call(ServiceName.FOOD, () -> api.get(
                    new IndustryAmendmentSourceInternalApi.Query(reference, sourceReturnNo)));
            if (result == null || !reference.equals(result.reference()) || !sourceReturnNo.equals(result.returnNo()))
                throw BizException.of(ErrorCode.CONFLICT);
            return result;
        };
    }
    @Bean @ConditionalOnMissingBean(DiningBatchQueryService.class)
    DiningBatchQueryService remoteDiningBatchQueryService(InternalHttp http) {
        return new RemoteDiningBatchQueryService(http.client(ServiceName.FOOD,
                DiningBatchQueryInternalApi.class, Duration.ofSeconds(5)));
    }
    @Bean @ConditionalOnMissingBean(BusinessReturnService.class)
    BusinessReturnService remoteBusinessReturnService(InternalHttp http) {
        return new RemoteBusinessReturnService(http.client(ServiceName.FOOD,
                BusinessReturnInternalApi.class, Duration.ofSeconds(5)));
    }
    @Bean @ConditionalOnMissingBean(BusinessShareService.class)
    BusinessShareService remoteBusinessShareService(InternalHttp http) {
        return new RemoteBusinessShareService(http.client(ServiceName.FOOD,
                BusinessShareInternalApi.class, Duration.ofSeconds(5)));
    }

    @Bean @ConditionalOnMissingBean(QueueService.class)
    QueueService remoteQueueService(InternalHttp http) {
        return new RemoteQueueService(http.client(ServiceName.FOOD,QueueInternalApi.class,Duration.ofSeconds(5)));
    }
    @Bean @ConditionalOnMissingBean(DiningPickupVerificationService.class)
    DiningPickupVerificationService remoteDiningPickupVerificationService(InternalHttp http) {
        return new RemoteDiningPickupVerificationService(http.client(ServiceName.FOOD,DiningPickupVerificationInternalApi.class,Duration.ofSeconds(5)));
    }

    @Bean @ConditionalOnMissingBean(ProductionConsumptionService.class)
    ProductionConsumptionService remoteProductionConsumptionService(InternalHttp http) {
        return new RemoteProductionConsumptionService(http.client(ServiceName.FOOD,ProductionConsumptionInternalApi.class,Duration.ofSeconds(5)));
    }
    @Bean @ConditionalOnMissingBean(KitchenReturnService.class)
    KitchenReturnService remoteKitchenReturnService(InternalHttp http) {
        return new RemoteKitchenReturnService(http.client(ServiceName.FOOD,KitchenReturnInternalApi.class,Duration.ofSeconds(5)));
    }

    @Bean @ConditionalOnMissingBean(KitchenTaskService.class)
    KitchenTaskService remoteKitchenTaskService(InternalHttp http) {
        return new RemoteKitchenTaskService(http.client(ServiceName.FOOD,KitchenInternalApi.class,Duration.ofSeconds(5)));
    }
    @Bean @ConditionalOnMissingBean(BusinessCloseService.class)
    BusinessCloseService remoteBusinessCloseService(InternalHttp http) {
        return new RemoteBusinessCloseService(http.client(ServiceName.FOOD,BusinessCloseInternalApi.class,Duration.ofSeconds(5)));
    }
    @Bean @ConditionalOnMissingBean(FoodPrintService.class)
    FoodPrintService remoteFoodPrintService(InternalHttp http) {
        return new RemoteFoodPrintService(http.client(ServiceName.FOOD,FoodPrintInternalApi.class,Duration.ofSeconds(5)));
    }

    @Bean
    @ConditionalOnMissingBean(DiningTransactionRecoveryService.class)
    DiningTransactionRecoveryService remoteDiningTransactionRecoveryService(InternalHttp http) {
        return new RemoteDiningTransactionRecoveryService(http.client(ServiceName.FOOD,
                DiningTransactionRecoveryInternalApi.class, Duration.ofSeconds(5)));
    }

    @Bean
    @ConditionalOnMissingBean(DiningCheckoutService.class)
    DiningCheckoutService remoteDiningCheckoutService(InternalHttp http) {
        return new RemoteDiningCheckoutService(http.client(ServiceName.FOOD, DiningCheckoutInternalApi.class,
                Duration.ofSeconds(5)));
    }
    @Bean
    @ConditionalOnMissingBean(DiningReturnService.class)
    DiningReturnService remoteDiningReturnService(InternalHttp http) {
        return new RemoteDiningReturnService(http.client(ServiceName.FOOD, DiningReturnInternalApi.class,
                Duration.ofSeconds(5)));
    }
    @Bean
    @ConditionalOnMissingBean(DiningOrderService.class)
    DiningOrderService remoteDiningOrderService(InternalHttp http) {
        return new RemoteDiningOrderService(http.client(ServiceName.FOOD, DiningOrderInternalApi.class,
                Duration.ofSeconds(5)));
    }
    @Bean
    @ConditionalOnMissingBean(DiningPricingService.class)
    DiningPricingService remoteDiningPricingService(InternalHttp http) {
        return new RemoteDiningPricingService(http.client(ServiceName.FOOD, DiningPricingInternalApi.class,
                Duration.ofSeconds(5)));
    }
    @Bean
    @ConditionalOnMissingBean(DiningOrderAuthorityService.class)
    DiningOrderAuthorityService remoteDiningOrderAuthorityService(InternalHttp http) {
        return new RemoteDiningOrderAuthorityService(http.client(ServiceName.FOOD,
                DiningOrderAuthorityInternalApi.class, Duration.ofSeconds(5)));
    }
    @Bean
    @ConditionalOnMissingBean(DiningPricingFactsService.class)
    DiningPricingFactsService remoteDiningPricingFactsService(InternalHttp http) {
        return new RemoteDiningPricingFactsService(http.client(ServiceName.FOOD, PricingSourceInternalApi.class,
                Duration.ofSeconds(5)));
    }
    @Bean
    @ConditionalOnMissingBean(CatalogService.class)
    CatalogService remoteCatalogService(InternalHttp http) {
        return new RemoteCatalogService(http.client(ServiceName.FOOD, CatalogInternalApi.class, Duration.ofSeconds(5)));
    }
    @Bean
    @ConditionalOnMissingBean(MenuChannelBindingService.class)
    MenuChannelBindingService remoteMenuChannelBindingService(InternalHttp http) {
        return new RemoteMenuChannelBindingService(http.client(ServiceName.FOOD, MenuChannelBindingInternalApi.class,
                Duration.ofSeconds(5)));
    }
    @Bean
    @ConditionalOnMissingBean(StationService.class)
    StationService remoteStationService(InternalHttp http) {
        return new RemoteStationService(http.client(ServiceName.FOOD, StationInternalApi.class, Duration.ofSeconds(5)));
    }
    @Bean
    @ConditionalOnMissingBean(SalesRestrictionService.class)
    SalesRestrictionService remoteSalesRestrictionService(InternalHttp http) {
        return new RemoteSalesRestrictionService(http.client(ServiceName.FOOD, SalesRestrictionInternalApi.class,
                Duration.ofSeconds(5)));
    }
    @Bean
    @ConditionalOnMissingBean(ConfigRevisionService.class)
    ConfigRevisionService remoteConfigRevisionService(InternalHttp http) {
        return new RemoteConfigRevisionService(http.client(ServiceName.FOOD, ConfigRevisionInternalApi.class,
                Duration.ofSeconds(5)));
    }

    @Bean
    @ConditionalOnMissingBean(MenuService.class)
    MenuService remoteMenuService(InternalHttp http) {
        return new RemoteMenuService(http.client(ServiceName.FOOD, MenuInternalApi.class, Duration.ofSeconds(5)));
    }

    @Bean
    @ConditionalOnMissingBean(RecipeService.class)
    RecipeService remoteRecipeService(InternalHttp http) {
        return new RemoteRecipeService(http.client(ServiceName.FOOD, RecipeInternalApi.class, Duration.ofSeconds(5)));
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
    @ConditionalOnMissingBean(CheckCartService.class)
    CheckCartService remoteCheckCartService(InternalHttp http) {
        return new RemoteCheckCartService(http.client(ServiceName.FOOD,
                CartInternalApi.class, Duration.ofSeconds(5)));
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
