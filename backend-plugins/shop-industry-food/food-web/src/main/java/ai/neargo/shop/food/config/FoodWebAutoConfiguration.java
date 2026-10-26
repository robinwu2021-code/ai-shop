package ai.neargo.shop.food.config;

import ai.neargo.shop.food.api.biz.BizKitchenTaskController;
import ai.neargo.shop.food.api.biz.BizDiningHandoverController;
import ai.neargo.shop.food.api.biz.BizCourseController;
import ai.neargo.shop.food.api.biz.BizBusinessCloseController;
import ai.neargo.shop.food.api.biz.BizPrintJobController;
import ai.neargo.shop.food.api.biz.BizPrintDeviceController;
import ai.neargo.shop.food.api.biz.BizPrintConfigurationController;
import ai.neargo.shop.food.event.DiningKitchenAcceptedConsumer;
import ai.neargo.shop.food.service.KitchenTaskService;
import ai.neargo.shop.food.service.QueueService;
import ai.neargo.shop.food.service.DiningPickupVerificationService;
import ai.neargo.shop.food.api.biz.BizQueueController;
import ai.neargo.shop.food.api.biz.BizDiningPickupVerificationController;
import ai.neargo.shop.food.service.ProductionConsumptionService;
import ai.neargo.shop.food.service.KitchenReturnService;
import ai.neargo.shop.food.api.biz.BizProductionConsumptionController;
import ai.neargo.shop.food.api.biz.BizKitchenReturnController;
import ai.neargo.shop.food.event.DiningProductionConsumer;
import ai.neargo.shop.food.service.BusinessCloseService;
import ai.neargo.shop.food.service.DiningReportService;
import ai.neargo.shop.food.api.biz.BizDiningReportController;
import ai.neargo.shop.food.api.biz.BizDiningReportExportController;
import ai.neargo.shop.food.service.FoodPrintService;
import ai.neargo.shop.spi.printing.PrintPort;
import tools.jackson.databind.ObjectMapper;


import ai.neargo.shop.food.api.FoodBizDataScopeInterceptor;
import ai.neargo.shop.food.api.FoodRequestScopeValidator;
import ai.neargo.shop.food.api.biz.BizCheckActionController;
import ai.neargo.shop.food.api.biz.BizCartController;
import ai.neargo.shop.food.api.biz.BizCheckController;
import ai.neargo.shop.food.api.biz.BizCheckSeatController;
import ai.neargo.shop.food.api.biz.BizReservationController;
import ai.neargo.shop.food.api.biz.BizServiceCalendarController;
import ai.neargo.shop.food.api.biz.BizStoreOperationPolicyController;
import ai.neargo.shop.food.api.biz.BizTableAreaController;
import ai.neargo.shop.food.api.biz.BizTableController;
import ai.neargo.shop.food.api.biz.BizTableOperationController;
import ai.neargo.shop.food.api.biz.BizTableSharingController;
import ai.neargo.shop.food.api.biz.BizTableTokenController;
import ai.neargo.shop.food.api.mp.MpCartController;
import ai.neargo.shop.food.api.mp.MpDiningSessionController;
import ai.neargo.shop.food.foodclient.FoodClientConfiguration;
import ai.neargo.shop.food.service.DiningPricingFactsService;
import ai.neargo.shop.food.service.DiningOrderAuthorityService;
import ai.neargo.shop.food.service.BusinessReturnApprovalService;
import ai.neargo.shop.food.api.biz.BizBusinessReturnApprovalController;
import ai.neargo.shop.food.port.FoodPricingSourceFactsPortImpl;
import ai.neargo.shop.food.service.CheckCartService;
import ai.neargo.shop.food.service.DiningPricingService;
import ai.neargo.shop.food.service.DiningOrderService;
import ai.neargo.shop.food.service.DiningReturnService;
import ai.neargo.shop.food.service.DiningCheckoutService;
import ai.neargo.shop.food.job.DiningTransactionRecoveryJob;
import ai.neargo.shop.food.job.FoodMaintenanceJobs;
import ai.neargo.shop.food.api.biz.BizSettlementController;
import ai.neargo.shop.food.api.mp.MpSettlementController;
import ai.neargo.shop.food.api.biz.BizDiningReturnController;
import ai.neargo.shop.food.api.mp.MpDiningReturnController;
import ai.neargo.shop.food.api.biz.BizOrderBatchController;
import ai.neargo.shop.food.api.mp.MpOrderBatchController;
import ai.neargo.shop.food.api.biz.BizDiningPricingController;
import ai.neargo.shop.food.api.mp.MpDiningPricingController;
import ai.neargo.shop.food.service.DiningBatchQueryService;
import ai.neargo.shop.food.api.biz.BizDiningBatchQueryController;
import ai.neargo.shop.food.service.BusinessShareService;
import ai.neargo.shop.food.service.BusinessReturnService;
import ai.neargo.shop.food.api.biz.BizBusinessReturnController;
import ai.neargo.shop.food.api.biz.BizBusinessShareController;
import ai.neargo.shop.food.service.CheckService;
import ai.neargo.shop.food.service.DishService;
import ai.neargo.shop.food.service.OptionGroupService;
import ai.neargo.shop.food.service.ModifierService;
import ai.neargo.shop.food.service.BundleService;
import ai.neargo.shop.food.service.RecipeService;
import ai.neargo.shop.food.service.MenuService;
import ai.neargo.shop.food.api.biz.BizCatalogController;
import ai.neargo.shop.food.api.mp.MpCatalogController;
import ai.neargo.shop.food.service.CatalogService;
import ai.neargo.shop.food.api.biz.BizMenuChannelBindingController;
import ai.neargo.shop.food.service.MenuChannelBindingService;
import ai.neargo.shop.food.api.biz.BizStationController;
import ai.neargo.shop.food.api.biz.BizSalesRestrictionController;
import ai.neargo.shop.food.service.StationService;
import ai.neargo.shop.food.service.SalesRestrictionService;
import ai.neargo.shop.food.api.biz.BizMenuController;
import ai.neargo.shop.food.api.biz.BizRecipeController;
import ai.neargo.shop.food.api.biz.BizBundleController;
import ai.neargo.shop.food.service.ConfigRevisionService;
import ai.neargo.shop.food.api.biz.BizConfigRevisionController;
import ai.neargo.shop.food.api.biz.BizModifierController;
import ai.neargo.shop.food.api.biz.BizOptionGroupController;
import ai.neargo.shop.food.api.biz.BizDishController;
import ai.neargo.shop.food.service.DiningSessionService;
import ai.neargo.shop.food.service.ReservationService;
import ai.neargo.shop.food.service.StoreOperationService;
import ai.neargo.shop.food.service.TableService;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 餐饮模块装配入口。 */
@AutoConfiguration(afterName = {"ai.neargo.shop.food.config.FoodAutoConfiguration",
        "ai.neargo.shop.food.config.FoodBusinessAmendmentConfiguration",
        "ai.neargo.shop.food.config.FoodBusinessOperationsConfiguration",
        "ai.neargo.shop.food.config.DiningIdentityAutoConfiguration",
        "ai.neargo.shop.food.config.DiningDeliveryAutoConfiguration",
        "ai.neargo.shop.food.config.FoodOrderAuthorityConfiguration",
        "ai.neargo.shop.food.config.FoodMaintenanceConfiguration"})
@Import({FoodClientConfiguration.class, ai.neargo.shop.food.foodclient.FoodScopeSigningConfiguration.class, DiningTransactionRecoveryJob.class, FoodMaintenanceJobs.class,
        FoodBusinessOperationsWebConfiguration.class})
@ConditionalOnFoodWebEnabled
@Profile({"api", "ops"})
public class FoodWebAutoConfiguration {
    @Bean @Profile("api") @Conditional(PolicyEnabled.class)
    @ConditionalOnBean({BusinessReturnApprovalService.class, StoreDirectoryPort.class})
    @ConditionalOnMissingBean(BizBusinessReturnApprovalController.class)
    BizBusinessReturnApprovalController bizBusinessReturnApprovalController(BusinessReturnApprovalService service,
            StoreDirectoryPort stores, FoodRequestScopeValidator validator) {
        return new BizBusinessReturnApprovalController(service, stores, validator);
    }
    @Bean @Profile("api") @Conditional(PolicyEnabled.class)
    @ConditionalOnBean({DiningBatchQueryService.class, StoreDirectoryPort.class})
    @ConditionalOnMissingBean(BizDiningBatchQueryController.class)
    BizDiningBatchQueryController diningBatchQueryController(DiningBatchQueryService service,
            StoreDirectoryPort stores, FoodRequestScopeValidator validator) {
        return new BizDiningBatchQueryController(service, stores, validator);
    }
    @Bean @Profile("api") @Conditional(PolicyEnabled.class)
    @ConditionalOnBean({BusinessReturnService.class, StoreDirectoryPort.class})
    @ConditionalOnMissingBean(BizBusinessReturnController.class)
    BizBusinessReturnController businessReturnController(BusinessReturnService service,
            StoreDirectoryPort stores, FoodRequestScopeValidator validator) {
        return new BizBusinessReturnController(service, stores, validator);
    }
    @Bean @Profile("api") @Conditional(PolicyEnabled.class)
    @ConditionalOnBean({BusinessShareService.class, StoreDirectoryPort.class})
    @ConditionalOnMissingBean(BizBusinessShareController.class)
    BizBusinessShareController businessShareController(BusinessShareService service,
            StoreDirectoryPort stores, FoodRequestScopeValidator validator) {
        return new BizBusinessShareController(service, stores, validator);
    }

    @Bean @Profile("api") @Conditional(PolicyEnabled.class)
    @ConditionalOnBean({QueueService.class,StoreDirectoryPort.class})
    @ConditionalOnMissingBean(BizQueueController.class)
    BizQueueController bizQueueController(QueueService service,StoreDirectoryPort stores,
            FoodRequestScopeValidator scope) { return new BizQueueController(service,stores,scope); }
    @Bean @Profile("api") @Conditional(PolicyEnabled.class)
    @ConditionalOnBean({DiningPickupVerificationService.class,StoreDirectoryPort.class})
    @ConditionalOnMissingBean(BizDiningPickupVerificationController.class)
    BizDiningPickupVerificationController bizDiningPickupVerificationController(DiningPickupVerificationService service,StoreDirectoryPort stores,
            FoodRequestScopeValidator scope) { return new BizDiningPickupVerificationController(service,stores,scope); }

    @Bean @Profile("api") @Conditional(PolicyEnabled.class)
    @ConditionalOnBean({KitchenTaskService.class,StoreDirectoryPort.class})
    @ConditionalOnMissingBean(BizDiningHandoverController.class)
    BizDiningHandoverController bizDiningHandoverController(KitchenTaskService service,StoreDirectoryPort stores,
            FoodRequestScopeValidator scope) { return new BizDiningHandoverController(service,stores,scope); }

    @Bean @Profile("api") @Conditional(PolicyEnabled.class)
    @ConditionalOnBean({ProductionConsumptionService.class,StoreDirectoryPort.class})
    @ConditionalOnMissingBean(BizProductionConsumptionController.class)
    BizProductionConsumptionController bizProductionConsumptionController(ProductionConsumptionService service,StoreDirectoryPort stores,
            FoodRequestScopeValidator scope) { return new BizProductionConsumptionController(service,stores,scope); }
    @Bean @Profile("api") @Conditional(PolicyEnabled.class)
    @ConditionalOnBean({KitchenReturnService.class,StoreDirectoryPort.class})
    @ConditionalOnMissingBean(BizKitchenReturnController.class)
    BizKitchenReturnController bizKitchenReturnController(KitchenReturnService service,StoreDirectoryPort stores,
            FoodRequestScopeValidator scope) { return new BizKitchenReturnController(service,stores,scope); }
    @Bean @Profile("api") @Conditional(PolicyEnabled.class)
    @ConditionalOnBean(ProductionConsumptionService.class)
    @ConditionalOnMissingBean(DiningProductionConsumer.class)
    DiningProductionConsumer diningProductionConsumer(ProductionConsumptionService service,ObjectMapper json) {
        return new DiningProductionConsumer(service,json);
    }

    @Bean @Profile("api") @Conditional(PolicyEnabled.class)
    @ConditionalOnBean({KitchenTaskService.class,StoreDirectoryPort.class})
    @ConditionalOnMissingBean(BizKitchenTaskController.class)
    BizKitchenTaskController bizKitchenTaskController(KitchenTaskService service,StoreDirectoryPort stores,
            FoodRequestScopeValidator scope) { return new BizKitchenTaskController(service,stores,scope); }
    @Bean @Profile("api") @Conditional(PolicyEnabled.class)
    @ConditionalOnProperty(name="shop.report.food.enabled",havingValue="true")
    @ConditionalOnBean({DiningReportService.class,StoreDirectoryPort.class})
    @ConditionalOnMissingBean(BizDiningReportController.class)
    BizDiningReportController bizDiningReportController(DiningReportService service,StoreDirectoryPort stores,
            FoodRequestScopeValidator scope) { return new BizDiningReportController(service,stores,scope); }

    @Bean @Profile("api") @Conditional(PolicyEnabled.class)
    @ConditionalOnProperty(name={"shop.report.food.enabled","shop.report.export.enabled"},havingValue="true")
    @ConditionalOnBean({DiningReportService.class,StoreDirectoryPort.class})
    @ConditionalOnMissingBean(BizDiningReportExportController.class)
    BizDiningReportExportController bizDiningReportExportController(DiningReportService service,StoreDirectoryPort stores,
            FoodRequestScopeValidator scope) { return new BizDiningReportExportController(service,stores,scope); }

    @Bean @Profile("api") @Conditional(PolicyEnabled.class)
    @ConditionalOnBean({KitchenTaskService.class,StoreDirectoryPort.class})
    @ConditionalOnMissingBean(BizCourseController.class)
    BizCourseController bizCourseController(KitchenTaskService service,StoreDirectoryPort stores,
            FoodRequestScopeValidator scope) { return new BizCourseController(service,stores,scope); }
    @Bean @Profile("api") @Conditional(PolicyEnabled.class)
    @ConditionalOnBean({BusinessCloseService.class,StoreDirectoryPort.class})
    @ConditionalOnMissingBean(BizBusinessCloseController.class)
    BizBusinessCloseController bizBusinessCloseController(BusinessCloseService service,StoreDirectoryPort stores,
            FoodRequestScopeValidator scope) { return new BizBusinessCloseController(service,stores,scope); }
    @Bean @Profile("api") @Conditional(PolicyEnabled.class)
    @ConditionalOnBean({PrintPort.class,StoreDirectoryPort.class})
    @ConditionalOnMissingBean(BizPrintDeviceController.class)
    BizPrintDeviceController bizPrintDeviceController(PrintPort service,StoreDirectoryPort stores,
            FoodRequestScopeValidator scope) { return new BizPrintDeviceController(service,stores,scope); }
    @Bean @Profile("api") @Conditional(PolicyEnabled.class)
    @ConditionalOnBean({PrintPort.class,StoreDirectoryPort.class})
    @ConditionalOnMissingBean(BizPrintConfigurationController.class)
    BizPrintConfigurationController bizPrintConfigurationController(PrintPort service,StoreDirectoryPort stores,
            FoodRequestScopeValidator scope) { return new BizPrintConfigurationController(service,stores,scope); }
    @Bean @Profile("api") @Conditional(PolicyEnabled.class)
    @ConditionalOnBean({FoodPrintService.class,PrintPort.class,StoreDirectoryPort.class})
    @ConditionalOnMissingBean(BizPrintJobController.class)
    BizPrintJobController bizPrintJobController(FoodPrintService service,PrintPort print,
            StoreDirectoryPort stores,FoodRequestScopeValidator scope) {
        return new BizPrintJobController(service,print,stores,scope);
    }
    @Bean @Profile("api") @Conditional(PolicyEnabled.class)
    @ConditionalOnBean(KitchenTaskService.class)
    @ConditionalOnMissingBean(DiningKitchenAcceptedConsumer.class)
    DiningKitchenAcceptedConsumer diningKitchenAcceptedConsumer(KitchenTaskService kitchen,ObjectMapper json,
            ObjectProvider<PrintPort> printing,ObjectProvider<FoodPrintService> documents) {
        return new DiningKitchenAcceptedConsumer(kitchen,json,printing::getIfAvailable,documents::getIfAvailable);
    }

    @Bean
    @Profile("api")
    @Conditional(PolicyEnabled.class)
    @ConditionalOnBean({DiningCheckoutService.class, StoreDirectoryPort.class})
    @ConditionalOnMissingBean(BizSettlementController.class)
    BizSettlementController diningCheckoutController(DiningCheckoutService service, StoreDirectoryPort stores,
            FoodRequestScopeValidator scope) { return new BizSettlementController(service, stores, scope); }

    @Bean
    @Profile("api")
    @Conditional(PolicyEnabled.class)
    @ConditionalOnBean(DiningCheckoutService.class)
    @ConditionalOnMissingBean(MpSettlementController.class)
    MpSettlementController mpDiningCheckoutController(DiningCheckoutService service) {
        return new MpSettlementController(service);
    }

    @Bean
    @Profile("api")
    @Conditional(PolicyEnabled.class)
    @ConditionalOnBean({DiningReturnService.class, StoreDirectoryPort.class})
    @ConditionalOnMissingBean(BizDiningReturnController.class)
    BizDiningReturnController diningReturnController(DiningReturnService service, StoreDirectoryPort stores,
            FoodRequestScopeValidator scope) { return new BizDiningReturnController(service, stores, scope); }

    @Bean
    @Profile("api")
    @Conditional(PolicyEnabled.class)
    @ConditionalOnBean(DiningReturnService.class)
    @ConditionalOnMissingBean(MpDiningReturnController.class)
    MpDiningReturnController mpDiningReturnController(DiningReturnService service) {
        return new MpDiningReturnController(service);
    }

    @Bean
    @Profile("api")
    @Conditional(PolicyEnabled.class)
    @ConditionalOnBean({DiningOrderService.class, StoreDirectoryPort.class})
    @ConditionalOnMissingBean(BizOrderBatchController.class)
    BizOrderBatchController orderBatchController(DiningOrderService service, StoreDirectoryPort stores,
            FoodRequestScopeValidator scope) { return new BizOrderBatchController(service, stores, scope); }

    @Bean
    @Profile("api")
    @Conditional(PolicyEnabled.class)
    @ConditionalOnBean(DiningOrderService.class)
    @ConditionalOnMissingBean(MpOrderBatchController.class)
    MpOrderBatchController mpOrderBatchController(DiningOrderService service) {
        return new MpOrderBatchController(service);
    }

    @Bean
    @Profile("api")
    @Conditional(PolicyEnabled.class)
    @ConditionalOnBean({DiningPricingService.class, StoreDirectoryPort.class})
    @ConditionalOnMissingBean(BizDiningPricingController.class)
    BizDiningPricingController diningPricingController(DiningPricingService service, StoreDirectoryPort stores,
            FoodRequestScopeValidator scope) {
        return new BizDiningPricingController(service, stores, scope);
    }

    @Bean
    @Profile("api")
    @Conditional(PolicyEnabled.class)
    @ConditionalOnBean(DiningPricingService.class)
    @ConditionalOnMissingBean(MpDiningPricingController.class)
    MpDiningPricingController mpDiningPricingController(DiningPricingService service) {
        return new MpDiningPricingController(service);
    }

    @Bean
    @Conditional(PolicyEnabled.class)
    @ConditionalOnBean(DiningPricingFactsService.class)
    @ConditionalOnMissingBean(FoodPricingSourceFactsPortImpl.class)
    FoodPricingSourceFactsPortImpl foodPricingSourceFactsPort(DiningPricingFactsService service,
            ObjectProvider<DiningOrderService> orders, ObjectProvider<DiningOrderAuthorityService> authority) {
        return new FoodPricingSourceFactsPortImpl(service, orders::getIfUnique, authority::getIfUnique);
    }

    @Bean
    @Profile("api")
    @Conditional(PolicyEnabled.class)
    @ConditionalOnBean({ConfigRevisionService.class, StoreDirectoryPort.class})
    @ConditionalOnMissingBean(BizConfigRevisionController.class)
    BizConfigRevisionController configRevisionController(ConfigRevisionService service, StoreDirectoryPort stores,
            FoodRequestScopeValidator scope) {
        return new BizConfigRevisionController(service, stores, scope);
    }

    @Bean
    @Profile("api")
    @Conditional(PolicyEnabled.class)
    @ConditionalOnBean({CatalogService.class, StoreDirectoryPort.class})
    @ConditionalOnMissingBean(BizCatalogController.class)
    BizCatalogController catalogController(CatalogService service, StoreDirectoryPort stores,
            FoodRequestScopeValidator scope) { return new BizCatalogController(service, stores, scope); }

    @Bean
    @Profile("api")
    @Conditional(PolicyEnabled.class)
    @ConditionalOnBean(CatalogService.class)
    @ConditionalOnMissingBean(MpCatalogController.class)
    MpCatalogController foodMpCatalogController(CatalogService service) { return new MpCatalogController(service); }

    @Bean
    @Profile("api")
    @Conditional(PolicyEnabled.class)
    @ConditionalOnBean({MenuChannelBindingService.class, StoreDirectoryPort.class})
    @ConditionalOnMissingBean(BizMenuChannelBindingController.class)
    BizMenuChannelBindingController menuChannelBindingController(MenuChannelBindingService service,
            StoreDirectoryPort stores, FoodRequestScopeValidator scope) {
        return new BizMenuChannelBindingController(service, stores, scope);
    }

    @Bean
    @Profile("api")
    @Conditional(PolicyEnabled.class)
    @ConditionalOnBean({StationService.class, StoreDirectoryPort.class})
    @ConditionalOnMissingBean(BizStationController.class)
    BizStationController stationController(StationService service, StoreDirectoryPort stores,
            FoodRequestScopeValidator scope) { return new BizStationController(service, stores, scope); }

    @Bean
    @Profile("api")
    @Conditional(PolicyEnabled.class)
    @ConditionalOnBean({SalesRestrictionService.class, StoreDirectoryPort.class})
    @ConditionalOnMissingBean(BizSalesRestrictionController.class)
    BizSalesRestrictionController restrictionController(SalesRestrictionService service, StoreDirectoryPort stores,
            FoodRequestScopeValidator scope) { return new BizSalesRestrictionController(service, stores, scope); }

    @Bean
    @Profile("api")
    @Conditional(PolicyEnabled.class)
    @ConditionalOnBean({MenuService.class, StoreDirectoryPort.class})
    @ConditionalOnMissingBean(BizMenuController.class)
    BizMenuController menuController(MenuService service, StoreDirectoryPort stores,
            FoodRequestScopeValidator scope) {
        return new BizMenuController(service, stores, scope);
    }

    @Bean
    @Profile("api")
    @Conditional(PolicyEnabled.class)
    @ConditionalOnBean({RecipeService.class, StoreDirectoryPort.class})
    @ConditionalOnMissingBean(BizRecipeController.class)
    BizRecipeController recipeController(RecipeService service, StoreDirectoryPort stores,
            FoodRequestScopeValidator scope) {
        return new BizRecipeController(service, stores, scope);
    }

    @Bean
    @Profile("api")
    @Conditional(PolicyEnabled.class)
    @ConditionalOnBean({BundleService.class, StoreDirectoryPort.class})
    @ConditionalOnMissingBean(BizBundleController.class)
    BizBundleController bundleController(BundleService service, StoreDirectoryPort stores,
            FoodRequestScopeValidator scope) {
        return new BizBundleController(service, stores, scope);
    }

    @Bean
    @Profile("api")
    @Conditional(PolicyEnabled.class)
    @ConditionalOnBean({ModifierService.class, StoreDirectoryPort.class})
    @ConditionalOnMissingBean(BizModifierController.class)
    BizModifierController modifierController(ModifierService service, StoreDirectoryPort stores,
            FoodRequestScopeValidator scope) {
        return new BizModifierController(service, stores, scope);
    }

    @Bean
    @Profile("api")
    @Conditional(PolicyEnabled.class)
    @ConditionalOnBean({OptionGroupService.class, StoreDirectoryPort.class})
    @ConditionalOnMissingBean(BizOptionGroupController.class)
    BizOptionGroupController optionGroupController(OptionGroupService service, StoreDirectoryPort stores,
            FoodRequestScopeValidator scope) {
        return new BizOptionGroupController(service, stores, scope);
    }

    @Bean
    @Profile("api")
    @Conditional(PolicyEnabled.class)
    @ConditionalOnBean({DishService.class, StoreDirectoryPort.class})
    @ConditionalOnMissingBean(BizDishController.class)
    BizDishController dishController(DishService service, StoreDirectoryPort stores, FoodRequestScopeValidator scope) {
        return new BizDishController(service, stores, scope);
    }

    @Bean
    @Profile("api")
    @Conditional(PolicyEnabled.class)
    @ConditionalOnBean({StoreOperationService.class, StoreDirectoryPort.class})
    @ConditionalOnMissingBean(BizStoreOperationPolicyController.class)
    BizStoreOperationPolicyController policyController(StoreOperationService service, StoreDirectoryPort stores,
            FoodRequestScopeValidator scope) {
        return new BizStoreOperationPolicyController(service, stores, scope);
    }

    @Bean
    @Profile("api")
    @Conditional(PolicyEnabled.class)
    @ConditionalOnBean({StoreOperationService.class, StoreDirectoryPort.class})
    @ConditionalOnMissingBean(BizServiceCalendarController.class)
    BizServiceCalendarController calendarController(StoreOperationService service, StoreDirectoryPort stores,
            FoodRequestScopeValidator scope) {
        return new BizServiceCalendarController(service, stores, scope);
    }

    @Bean
    @Profile("api")
    @Conditional(PolicyEnabled.class)
    @ConditionalOnBean({TableService.class, StoreDirectoryPort.class})
    @ConditionalOnMissingBean(BizTableAreaController.class)
    BizTableAreaController tableAreaController(TableService service, StoreDirectoryPort stores,
            FoodRequestScopeValidator scope) {
        return new BizTableAreaController(service, stores, scope);
    }

    @Bean
    @Profile("api")
    @Conditional(PolicyEnabled.class)
    @ConditionalOnBean({TableService.class, StoreDirectoryPort.class})
    @ConditionalOnMissingBean(BizTableController.class)
    BizTableController tableController(TableService service, StoreDirectoryPort stores,
            FoodRequestScopeValidator scope) {
        return new BizTableController(service, stores, scope);
    }

    @Bean
    @Profile("api")
    @Conditional(PolicyEnabled.class)
    @ConditionalOnBean({DiningSessionService.class, StoreDirectoryPort.class})
    @ConditionalOnMissingBean(BizTableTokenController.class)
    BizTableTokenController tableTokenController(
            DiningSessionService service, StoreDirectoryPort stores,
            FoodRequestScopeValidator scope) {
        return new BizTableTokenController(service,stores,scope);
    }

    @Bean
    @Profile("api")
    @Conditional(PolicyEnabled.class)
    @ConditionalOnBean({DiningSessionService.class, StoreDirectoryPort.class})
    @ConditionalOnMissingBean(MpDiningSessionController.class)
    MpDiningSessionController diningSessionController(
            DiningSessionService service, StoreDirectoryPort stores) {
        return new MpDiningSessionController(service,stores);
    }

    @Bean
    @Profile("api")
    @Conditional(PolicyEnabled.class)
    @ConditionalOnBean({CheckCartService.class, StoreDirectoryPort.class})
    @ConditionalOnMissingBean(BizCartController.class)
    BizCartController bizCartController(CheckCartService service, StoreDirectoryPort stores,
            FoodRequestScopeValidator scope) {
        return new BizCartController(service, stores, scope);
    }

    @Bean
    @Profile("api")
    @Conditional(PolicyEnabled.class)
    @ConditionalOnBean(CheckCartService.class)
    @ConditionalOnMissingBean(MpCartController.class)
    MpCartController mpCartController(CheckCartService service) { return new MpCartController(service); }

    @Bean
    @Profile("api")
    @Conditional(PolicyEnabled.class)
    @ConditionalOnBean({CheckService.class, StoreDirectoryPort.class})
    @ConditionalOnMissingBean(BizCheckController.class)
    BizCheckController bizCheckController(
            CheckService service,StoreDirectoryPort stores,FoodRequestScopeValidator scope) {
        return new BizCheckController(service,stores,scope);
    }

    @Bean
    @Profile("api")
    @Conditional(PolicyEnabled.class)
    @ConditionalOnBean({CheckService.class, StoreDirectoryPort.class})
    @ConditionalOnMissingBean(BizCheckSeatController.class)
    BizCheckSeatController bizCheckSeatController(
            CheckService service,StoreDirectoryPort stores,FoodRequestScopeValidator scope) {
        return new BizCheckSeatController(service,stores,scope);
    }

    @Bean
    @Profile("api")
    @Conditional(PolicyEnabled.class)
    @ConditionalOnBean({CheckService.class, StoreDirectoryPort.class})
    @ConditionalOnMissingBean(BizTableSharingController.class)
    BizTableSharingController bizTableSharingController(
            CheckService service,StoreDirectoryPort stores,FoodRequestScopeValidator scope) {
        return new BizTableSharingController(service,stores,scope);
    }

    @Bean
    @Profile("api")
    @Conditional(PolicyEnabled.class)
    @ConditionalOnBean({CheckService.class, StoreDirectoryPort.class})
    @ConditionalOnMissingBean(BizTableOperationController.class)
    BizTableOperationController bizTableOperationController(
            CheckService service,StoreDirectoryPort stores,FoodRequestScopeValidator scope) {
        return new BizTableOperationController(service,stores,scope);
    }

    @Bean
    @Profile("api")
    @Conditional(PolicyEnabled.class)
    @ConditionalOnBean({CheckService.class, StoreDirectoryPort.class})
    @ConditionalOnMissingBean(BizCheckActionController.class)
    BizCheckActionController bizCheckActionController(
            CheckService service,StoreDirectoryPort stores,FoodRequestScopeValidator scope) {
        return new BizCheckActionController(service,stores,scope);
    }

    @Bean
    @Profile("api")
    @Conditional(PolicyEnabled.class)
    @ConditionalOnBean({ReservationService.class, StoreDirectoryPort.class})
    @ConditionalOnMissingBean(BizReservationController.class)
    BizReservationController reservationController(
            ReservationService service,StoreDirectoryPort stores,FoodRequestScopeValidator scope) {
        return new BizReservationController(service,stores,scope);
    }

    @Bean
    @Profile("api")
    @Conditional(PolicyEnabled.class)
    WebMvcConfigurer foodBizDataScopeConfigurer() {
        return new WebMvcConfigurer() {
            @Override public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new FoodBizDataScopeInterceptor()).addPathPatterns("/biz/x/food/**");
            }
        };
    }

    /** OFF is a nonbusiness skeleton mode; never expose a policy endpoint through it. */
    public static final class PolicyEnabled implements Condition {
        @Override public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return !"off".equalsIgnoreCase(context.getEnvironment().getProperty("shop.food.migration.mode", "validate"));
        }
    }

    @Bean
    FoodRequestScopeValidator foodRequestScopeValidator() {
        return new FoodRequestScopeValidator();
    }
}
