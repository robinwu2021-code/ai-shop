package ai.neargo.shop.food.config;

import ai.neargo.shop.food.mapper.FoodMappers.*;
import ai.neargo.shop.food.service.impl.FoodStockManagementServiceImpl;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

import ai.neargo.shop.food.port.FoodStockReportFactsPortAdapter;
import ai.neargo.shop.food.service.FoodReturnStockService;
import ai.neargo.shop.food.service.FoodStockSyncService;
import ai.neargo.shop.food.service.impl.FoodReturnStockServiceImpl;
import ai.neargo.shop.food.service.impl.FoodStockReconciliationServiceImpl;
import ai.neargo.shop.food.service.impl.FoodStockReservationServiceImpl;
import ai.neargo.shop.food.service.impl.FoodStockSyncDispatcher;
import ai.neargo.shop.food.service.impl.FoodStockSyncPublisher;
import ai.neargo.shop.food.service.impl.FoodStockSyncServiceImpl;
import ai.neargo.shop.food.service.impl.FoodStockSyncWorker;
import ai.neargo.shop.spi.food.FoodLocalStockPort;
import ai.neargo.shop.spi.food.FoodStockReportFactsPort;
import ai.neargo.shop.spi.inventory.InventoryFoodStockProjectionPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
/** Opt-in only until full owner routing and deployment verification are complete. */
@AutoConfiguration(after=FoodAutoConfiguration.class)
@ConditionalOnFoodEnabled
@Conditional(FoodPersistenceConfiguration.PersistenceEnabled.class)
@ConditionalOnProperty(name="shop.food.local-stock.enabled",havingValue="true")
public class FoodStockAutoConfiguration {
    @Bean @ConditionalOnProperty(name="shop.food.local-stock.report-source-zone")
    FoodStockReportFactsPort foodStockReportFactsPort(StockBalanceMapper balances,StockItemMapper items,
            StockMovementMapper movements,StockCommandMapper commands,ObjectMapper json,
            @Qualifier("transactionManager") PlatformTransactionManager manager,
            @Value("${shop.food.local-stock.report-source-zone}")String zone) {
        return new FoodStockReportFactsPortAdapter(balances,items,movements,commands,json,manager,zone);
    }
    @Bean FoodReturnStockService foodReturnStockService(ReturnStockDispositionMapper claims,
            ReturnMapper returns,ReturnItemMapper returnItems,ProductionConsumptionMapper production,ServiceEventMapper events,
            KitchenTaskMapper tasks,OrderBatchMapper batches,OrderLineMapper lines,StockCommandMapper commands,
            StockReservationMapper reservations,StockMovementMapper movements,FoodLocalStockPort stock,
            ObjectMapper json,@Qualifier("transactionManager") PlatformTransactionManager manager) {
        return new FoodReturnStockServiceImpl(claims,returns,returnItems,production,events,tasks,
                batches,lines,commands,reservations,movements,stock,json,manager);
    }
    @Bean FoodStockReconciliationServiceImpl foodStockReconciliationService(
            StockSyncReconciliationMapper tasks,StockSyncDifferenceMapper differences,StockSyncConfigMapper configs,
            StockSyncItemStateMapper states,StockBalanceMapper balances,StockItemMapper items,StockCommandMapper commands,
            FoodStockSyncPublisher publisher,
            ObjectProvider<InventoryFoodStockProjectionPort> receivers,
            ObjectMapper json,@Qualifier("transactionManager") PlatformTransactionManager manager) {
        return new FoodStockReconciliationServiceImpl(tasks,differences,configs,states,balances,items,commands,publisher,receivers,json,manager);
    }
    @Bean FoodStockSyncService foodStockSyncService(
            StockSyncConfigMapper configs,StockSyncMappingMapper mappings,StockSyncDeliveryMapper deliveries,
            StockItemMapper items,StockBalanceMapper balances,StockCommandMapper commands,ActionLogMapper logs,
            FoodStockSyncPublisher publisher,
            ObjectProvider<InventoryFoodStockProjectionPort> receivers,
            ObjectMapper json,@Qualifier("transactionManager") PlatformTransactionManager manager) {
        return new FoodStockSyncServiceImpl(configs,mappings,deliveries,items,balances,commands,logs,publisher,receivers,json,manager);
    }
    @Bean FoodStockSyncDispatcher foodStockSyncDispatcher(
            StockSyncConfigMapper configs,StockSyncDeliveryMapper deliveries,StockItemMapper items,
            StockBalanceMapper balances,FoodStockSyncPublisher publisher,
            ObjectProvider<InventoryFoodStockProjectionPort> receivers,
            ObjectMapper json,@Qualifier("transactionManager") PlatformTransactionManager manager,
            FoodStockReconciliationServiceImpl reconciliation) {
        return new FoodStockSyncDispatcher(configs,deliveries,items,balances,publisher,receivers,json,manager,reconciliation);
    }
    @Bean FoodStockSyncPublisher foodStockSyncPublisher(
            StockSyncConfigMapper configs,StockSyncItemStateMapper states,StockSyncMappingMapper mappings,
            StockSyncDeliveryMapper deliveries,StockItemMapper items,StockBalanceMapper balances,
            ObjectMapper json,ObjectProvider<FoodStockSyncWorker> workers) {
        var publisher=new FoodStockSyncPublisher(configs,states,mappings,deliveries,items,balances,json);
        publisher.delivery((entity,store)->workers.getObject().deliver(entity,store));
        return publisher;
    }
    @Bean FoodStockSyncWorker foodStockSyncWorker(
            StockSyncDeliveryMapper deliveries,FoodStockSyncDispatcher dispatcher,
            ObjectMapper json,@Qualifier("transactionManager") PlatformTransactionManager manager){
        return new FoodStockSyncWorker(deliveries,dispatcher,json,manager);
    }
    @Bean FoodLocalStockPort foodLocalStockPort(StockItemMapper items,
            StockBalanceMapper balances,StockReservationMapper heads,StockReservationItemMapper leaves,
            StockCommandMapper commands,StockMovementMapper movements,ObjectMapper json,
            FoodStockSyncPublisher sync,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        return new FoodStockReservationServiceImpl(items,balances,heads,leaves,commands,movements,json,manager,sync);
    }
    @Bean FoodStockManagementServiceImpl foodStockManagementService(StockItemMapper items,
            StockBalanceMapper balances,StockCommandMapper commands,StockMovementMapper movements,
            StockReservationItemMapper leaves,GoodsCatalogQueryPort catalog,ObjectMapper json,
            FoodStockSyncPublisher sync,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        return new FoodStockManagementServiceImpl(items,balances,commands,movements,leaves,catalog,json,manager,sync);
    }
}
