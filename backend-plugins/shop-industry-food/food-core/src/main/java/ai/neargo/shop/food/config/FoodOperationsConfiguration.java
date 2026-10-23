package ai.neargo.shop.food.config;

import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.food.service.QueueService;
import ai.neargo.shop.food.service.CheckService;
import ai.neargo.shop.food.service.StoreOperationService;
import ai.neargo.shop.food.service.DiningPickupVerificationService;
import ai.neargo.shop.food.service.impl.QueueServiceImpl;
import ai.neargo.shop.food.service.impl.DiningPickupVerificationServiceImpl;
import ai.neargo.shop.spi.trade.IndustryFulfillmentPort;
import ai.neargo.shop.food.service.ProductionConsumptionService;
import ai.neargo.shop.food.service.KitchenReturnService;
import ai.neargo.shop.food.service.impl.ProductionConsumptionServiceImpl;
import ai.neargo.shop.food.service.impl.KitchenReturnServiceImpl;
import ai.neargo.shop.spi.inventory.InventoryProductionPort;
import ai.neargo.shop.food.assembler.FoodPrintDocumentAssembler;
import ai.neargo.shop.food.mapper.FoodMappers;
import ai.neargo.shop.food.service.BusinessCloseService;
import ai.neargo.shop.food.service.KitchenTaskService;
import ai.neargo.shop.food.service.FoodPrintService;
import ai.neargo.shop.food.service.impl.BusinessCloseServiceImpl;
import ai.neargo.shop.food.service.impl.KitchenTaskServiceImpl;
import ai.neargo.shop.food.service.impl.FoodPrintServiceImpl;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.spi.printing.PrintPort;
import ai.neargo.shop.spi.user.StoreCardQueryPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/** Non-payment operations use original FOOD persistence and shared owners in both deployment forms. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnFoodEnabled
@Conditional(FoodPersistenceConfiguration.PersistenceEnabled.class)
public class FoodOperationsConfiguration {
    @Bean @ConditionalOnMissingBean(KitchenTaskService.class)
    KitchenTaskService kitchenTaskService(FoodMappers.OrderBatchMapper batches,FoodMappers.OrderLineMapper lines,
            FoodMappers.OrderComponentMapper components,FoodMappers.ConfigRevisionMapper revisions,
            FoodMappers.KitchenTicketMapper tickets,FoodMappers.KitchenTaskMapper tasks,
            FoodMappers.ServiceEventMapper events,FoodMappers.CourseMapper courses,FoodMappers.CheckMapper checks,
            IdempotencyService idem,ObjectMapper json,@Qualifier("transactionManager") PlatformTransactionManager manager,
            @Qualifier("foodOutboxPublisher") OutboxPublisher publisher) {
        return new KitchenTaskServiceImpl(batches,lines,components,revisions,tickets,tasks,events,courses,checks,
                idem,json,manager,publisher);
    }
    @Bean @ConditionalOnMissingBean(BusinessCloseService.class)
    BusinessCloseService businessCloseService(FoodMappers.BusinessDayMapper days,FoodMappers.CloseRunMapper runs,
            FoodMappers.CloseBalanceMapper balances,FoodMappers.CloseExceptionMapper exceptions,
            FoodMappers.CloseAdjustmentMapper adjustments,FoodMappers.StorePolicyMapper policies,
            FoodMappers.OrderBatchMapper batches,FoodMappers.OrderLineMapper lines,FoodMappers.SettlementMapper settlements,
            FoodMappers.SettlementItemMapper settlementItems,FoodMappers.CoreOrderBindingMapper bindings,
            FoodMappers.ReturnMapper returns,FoodMappers.KitchenTicketMapper tickets,FoodMappers.KitchenTaskMapper tasks,
            FoodMappers.ServiceEventMapper events,ObjectProvider<StoreCardQueryPort> stores,IdempotencyService idem,ObjectMapper json,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        return new BusinessCloseServiceImpl(days,runs,balances,exceptions,adjustments,policies,batches,lines,
                settlements,settlementItems,bindings,returns,tickets,tasks,events,numbers -> stores.getObject().cards(numbers),idem,json,manager);
    }
    @Bean @ConditionalOnMissingBean(FoodPrintDocumentAssembler.class)
    FoodPrintDocumentAssembler foodPrintDocumentAssembler(FoodMappers.OrderBatchMapper batches,
            FoodMappers.OrderLineMapper lines,FoodMappers.CoreLineAllocationMapper allocations,
            FoodMappers.ReturnMapper returns,FoodMappers.ReturnItemMapper items,FoodMappers.CheckMapper checks,
            KitchenTaskService kitchen,@Qualifier("transactionManager") PlatformTransactionManager manager) {
        return new FoodPrintDocumentAssembler(batches,lines,allocations,returns,items,checks,kitchen,manager);
    }
    @Bean @ConditionalOnMissingBean(FoodPrintService.class)
    FoodPrintService foodPrintService(FoodPrintDocumentAssembler documents,ObjectProvider<PrintPort> printing) {
        return new FoodPrintServiceImpl(documents,printing::getIfAvailable);
    }

    @Bean @ConditionalOnMissingBean(ProductionConsumptionService.class)
    ProductionConsumptionService productionConsumptionService(FoodMappers.ProductionConsumptionMapper heads,
            FoodMappers.ProductionConsumptionLineMapper lines,FoodMappers.OrderBatchMapper batches,
            FoodMappers.OrderLineMapper orderLines,FoodMappers.KitchenTicketMapper tickets,
            FoodMappers.KitchenTaskMapper tasks,FoodMappers.ServiceEventMapper events,
            FoodMappers.ConfigRevisionMapper revisions,ObjectProvider<StoreCardQueryPort> stores,ObjectProvider<InventoryProductionPort> inventory,
            ObjectMapper json,@Qualifier("transactionManager") PlatformTransactionManager manager) {
        return new ProductionConsumptionServiceImpl(heads,lines,batches,orderLines,tickets,tasks,events,
                revisions,numbers -> stores.getObject().cards(numbers),inventory.getIfAvailable(),json,manager);
    }
    @Bean @ConditionalOnMissingBean(KitchenReturnService.class)
    KitchenReturnService kitchenReturnService(FoodMappers.ReturnMapper returns,FoodMappers.ReturnItemMapper items,
            FoodMappers.OrderBatchMapper batches,FoodMappers.OrderLineMapper lines,
            FoodMappers.CoreLineAllocationMapper allocations,FoodMappers.ActionLogMapper actions,
            KitchenTaskService kitchen,ObjectMapper json,@Qualifier("transactionManager") PlatformTransactionManager manager,
            FoodMappers.KitchenTicketMapper tickets,FoodMappers.OrderComponentMapper components) {
        return new KitchenReturnServiceImpl(returns,items,batches,lines,allocations,actions,kitchen,json,manager,
                tickets,components);
    }

    @Bean @ConditionalOnMissingBean(QueueService.class)
    QueueService queueService(FoodMappers.QueueTicketMapper tickets,FoodMappers.StorePolicyMapper policies,
            FoodMappers.ActionLogMapper actions,StoreOperationService operations,CheckService checks,
            ObjectProvider<StoreCardQueryPort> stores,IdempotencyService idem,
            @Qualifier("foodOutboxPublisher") OutboxPublisher outbox,ObjectMapper json,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        return new QueueServiceImpl(tickets,policies,actions,operations,checks,numbers -> stores.getObject().cards(numbers),idem,outbox,json,manager);
    }
    @Bean @ConditionalOnMissingBean(DiningPickupVerificationService.class)
    DiningPickupVerificationService diningPickupVerificationService(FoodMappers.OrderBatchMapper batches,
            FoodMappers.CoreOrderBindingMapper bindings,KitchenTaskService kitchen,
            ObjectProvider<IndustryFulfillmentPort> fulfillment,FoodMappers.OrderLineMapper lines,
            FoodMappers.OrderComponentMapper components) {
        return new DiningPickupVerificationServiceImpl(batches,bindings,kitchen,fulfillment::getIfUnique,lines,components);
    }
}
