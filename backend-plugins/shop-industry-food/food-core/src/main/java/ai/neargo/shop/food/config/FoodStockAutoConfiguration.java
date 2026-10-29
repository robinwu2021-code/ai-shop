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

/** Opt-in only until full owner routing and deployment verification are complete. */
@AutoConfiguration(after=FoodAutoConfiguration.class)
@ConditionalOnFoodEnabled
@Conditional(FoodPersistenceConfiguration.PersistenceEnabled.class)
@ConditionalOnProperty(name="shop.food.local-stock.enabled",havingValue="true")
public class FoodStockAutoConfiguration {
    @Bean @ConditionalOnProperty(name="shop.food.local-stock.report-source-zone")
    ai.neargo.shop.spi.food.FoodStockReportFactsPort foodStockReportFactsPort(StockBalanceMapper balances,StockItemMapper items,
            StockMovementMapper movements,StockCommandMapper commands,ObjectMapper json,
            @Qualifier("transactionManager") PlatformTransactionManager manager,
            @org.springframework.beans.factory.annotation.Value("${shop.food.local-stock.report-source-zone}")String zone) {
        return new ai.neargo.shop.food.port.FoodStockReportFactsPortAdapter(balances,items,movements,commands,json,manager,zone);
    }
    @Bean ai.neargo.shop.food.service.FoodReturnStockService foodReturnStockService(ReturnStockDispositionMapper claims,
            ReturnMapper returns,ReturnItemMapper returnItems,ProductionConsumptionMapper production,ServiceEventMapper events,
            KitchenTaskMapper tasks,OrderBatchMapper batches,OrderLineMapper lines,StockCommandMapper commands,
            StockReservationMapper reservations,StockMovementMapper movements,ai.neargo.shop.spi.food.FoodLocalStockPort stock,
            ObjectMapper json,@Qualifier("transactionManager") PlatformTransactionManager manager) {
        return new ai.neargo.shop.food.service.impl.FoodReturnStockServiceImpl(claims,returns,returnItems,production,events,tasks,
                batches,lines,commands,reservations,movements,stock,json,manager);
    }
    @Bean ai.neargo.shop.food.service.impl.FoodStockReconciliationServiceImpl foodStockReconciliationService(
            StockSyncReconciliationMapper tasks,StockSyncDifferenceMapper differences,StockSyncConfigMapper configs,
            StockSyncItemStateMapper states,StockBalanceMapper balances,StockItemMapper items,StockCommandMapper commands,
            ai.neargo.shop.food.service.impl.FoodStockSyncPublisher publisher,
            org.springframework.beans.factory.ObjectProvider<ai.neargo.shop.spi.inventory.InventoryFoodStockProjectionPort> receivers,
            ObjectMapper json,@Qualifier("transactionManager") PlatformTransactionManager manager) {
        return new ai.neargo.shop.food.service.impl.FoodStockReconciliationServiceImpl(tasks,differences,configs,states,balances,items,commands,publisher,receivers,json,manager);
    }
    @Bean ai.neargo.shop.food.service.FoodStockSyncService foodStockSyncService(
            StockSyncConfigMapper configs,StockSyncMappingMapper mappings,StockSyncDeliveryMapper deliveries,
            StockItemMapper items,StockBalanceMapper balances,StockCommandMapper commands,ActionLogMapper logs,
            ai.neargo.shop.food.service.impl.FoodStockSyncPublisher publisher,
            org.springframework.beans.factory.ObjectProvider<ai.neargo.shop.spi.inventory.InventoryFoodStockProjectionPort> receivers,
            ObjectMapper json,@Qualifier("transactionManager") PlatformTransactionManager manager) {
        return new ai.neargo.shop.food.service.impl.FoodStockSyncServiceImpl(configs,mappings,deliveries,items,balances,commands,logs,publisher,receivers,json,manager);
    }
    @Bean ai.neargo.shop.food.service.impl.FoodStockSyncDispatcher foodStockSyncDispatcher(
            StockSyncConfigMapper configs,StockSyncDeliveryMapper deliveries,StockItemMapper items,
            StockBalanceMapper balances,ai.neargo.shop.food.service.impl.FoodStockSyncPublisher publisher,
            org.springframework.beans.factory.ObjectProvider<ai.neargo.shop.spi.inventory.InventoryFoodStockProjectionPort> receivers,
            ObjectMapper json,@Qualifier("transactionManager") PlatformTransactionManager manager,
            ai.neargo.shop.food.service.impl.FoodStockReconciliationServiceImpl reconciliation) {
        return new ai.neargo.shop.food.service.impl.FoodStockSyncDispatcher(configs,deliveries,items,balances,publisher,receivers,json,manager,reconciliation);
    }
    @Bean ai.neargo.shop.food.service.impl.FoodStockSyncPublisher foodStockSyncPublisher(
            StockSyncConfigMapper configs,StockSyncItemStateMapper states,StockSyncMappingMapper mappings,
            StockSyncDeliveryMapper deliveries,StockItemMapper items,StockBalanceMapper balances,
            @Qualifier("foodOutboxPublisher") ai.neargo.shop.event.outbox.OutboxPublisher outbox,ObjectMapper json) {
        return new ai.neargo.shop.food.service.impl.FoodStockSyncPublisher(configs,states,mappings,deliveries,items,balances,outbox,json);
    }
    @Bean ai.neargo.shop.spi.food.FoodLocalStockPort foodLocalStockPort(StockItemMapper items,
            StockBalanceMapper balances,StockReservationMapper heads,StockReservationItemMapper leaves,
            StockCommandMapper commands,StockMovementMapper movements,ObjectMapper json,
            ai.neargo.shop.food.service.impl.FoodStockSyncPublisher sync,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        return new ai.neargo.shop.food.service.impl.FoodStockReservationServiceImpl(items,balances,heads,leaves,commands,movements,json,manager,sync);
    }
    @Bean FoodStockManagementServiceImpl foodStockManagementService(StockItemMapper items,
            StockBalanceMapper balances,StockCommandMapper commands,StockMovementMapper movements,
            StockReservationItemMapper leaves,GoodsCatalogQueryPort catalog,ObjectMapper json,
            ai.neargo.shop.food.service.impl.FoodStockSyncPublisher sync,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        return new FoodStockManagementServiceImpl(items,balances,commands,movements,leaves,catalog,json,manager,sync);
    }
}
