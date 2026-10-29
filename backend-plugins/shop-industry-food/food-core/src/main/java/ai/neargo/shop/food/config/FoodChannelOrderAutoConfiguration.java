package ai.neargo.shop.food.config;

import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.food.lifecycle.ServiceSlotLifecycle;
import ai.neargo.shop.food.mapper.DiningChannelMappers.ChannelImportGuardMapper;
import ai.neargo.shop.food.mapper.DiningChannelMappers.ChannelOrderSourceMapper;
import ai.neargo.shop.food.mapper.DiningChannelMappers.ChannelPartyMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CheckCartItemMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CheckCartMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CoreLineAllocationMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CoreOrderBindingMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderBatchMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderComponentMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderLineMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderModifierMapper;
import ai.neargo.shop.food.service.DiningChannelOrderService;
import ai.neargo.shop.food.service.KitchenTaskService;
import ai.neargo.shop.food.service.SalesQuotaClaimService;
import ai.neargo.shop.food.service.StoreOperationService;
import ai.neargo.shop.food.service.impl.DiningChannelOrderBinder;
import ai.neargo.shop.food.service.impl.DiningChannelOrderServiceImpl;
import ai.neargo.shop.food.service.impl.DiningChannelProductionPlanBuilder;
import ai.neargo.shop.food.service.impl.DiningChannelSourceStore;
import ai.neargo.shop.food.spi.DiningChannelSourceFactsPort;
import ai.neargo.shop.spi.inventory.MaterialCatalogQueryPort;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

/**
 * Native channel admission is opt-in and uses original owner ports in both embedded and remote
 * deployments.
 */
@AutoConfiguration(
        after = FoodAutoConfiguration.class,
        afterName = {"ai.neargo.shop.food.channel.config.FoodPricingClientConfiguration"})
@ConditionalOnProperty(
        name = {"shop.food.enabled", "shop.food.channel.enabled"},
        havingValue = "true")
public class FoodChannelOrderAutoConfiguration {
    @Bean
    DiningChannelSourceStore diningChannelSourceStore(
            ChannelOrderSourceMapper sources,
            ChannelImportGuardMapper guards,
            ChannelPartyMapper parties,
            CheckCartMapper carts,
            CheckCartItemMapper items,
            OrderBatchMapper batches,
            SalesQuotaClaimService quotas,
            ObjectMapper json,
            @Qualifier("transactionManager") PlatformTransactionManager manager,
            ObjectProvider<Clock> clocks,
            ObjectProvider<ServiceSlotLifecycle> slots) {
        return new DiningChannelSourceStore(
                sources,
                guards,
                parties,
                carts,
                items,
                batches,
                quotas,
                json,
                manager,
                clocks.getIfAvailable(Clock::systemUTC),
                slots.getIfUnique());
    }

    @Bean
    DiningChannelProductionPlanBuilder diningChannelProductionPlanBuilder(
            DiningChannelSourceStore store, MaterialCatalogQueryPort materials, ObjectMapper json) {
        return new DiningChannelProductionPlanBuilder(store, materials, json);
    }

    @Bean
    DiningChannelOrderBinder diningChannelOrderBinder(
            DiningChannelSourceStore store,
            OrderLineMapper lines,
            OrderComponentMapper components,
            OrderModifierMapper modifiers,
            CoreOrderBindingMapper bindings,
            CoreLineAllocationMapper allocations,
            @Qualifier("foodOutboxPublisher") OutboxPublisher publisher) {
        return new DiningChannelOrderBinder(
                store, lines, components, modifiers, bindings, allocations, publisher);
    }

    @Bean
    @ConditionalOnMissingBean(DiningChannelOrderService.class)
    DiningChannelOrderService diningChannelOrderService(
            DiningChannelSourceStore store,
            IndustryChannelOrderPort core,
            ObjectProvider<DiningChannelSourceFactsPort> facts,
            StoreOperationService policies,
            KitchenTaskService kitchen,
            DiningChannelProductionPlanBuilder planner,
            DiningChannelOrderBinder binder) {
        return new DiningChannelOrderServiceImpl(
                store,
                core,
                () -> facts.orderedStream().toList(),
                policies,
                kitchen,
                planner,
                binder);
    }
}
