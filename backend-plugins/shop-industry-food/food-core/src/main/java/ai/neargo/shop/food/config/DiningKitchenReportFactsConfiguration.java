package ai.neargo.shop.food.config;

import ai.neargo.shop.food.mapper.FoodMappers.KitchenTaskMapper;
import ai.neargo.shop.food.mapper.FoodMappers.KitchenTicketMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderBatchMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderLineMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ServiceEventMapper;
import ai.neargo.shop.food.port.DiningKitchenReportFactsPortAdapter;
import ai.neargo.shop.food.service.DiningKitchenReportFactsService;
import ai.neargo.shop.food.service.DiningReportFactsService;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.transaction.PlatformTransactionManager;

/** Adds original kitchen reads to the existing report source collection. */
@AutoConfiguration(after = DiningReportFactsConfiguration.class)
@ConditionalOnFoodEnabled
@Conditional(FoodPersistenceConfiguration.PersistenceEnabled.class)
public class DiningKitchenReportFactsConfiguration {
    @Bean
    @ConditionalOnMissingBean(DiningKitchenReportFactsService.class)
    DiningKitchenReportFactsService diningKitchenReportFactsService(
            DiningReportFactsService days,
            ServiceEventMapper events,
            KitchenTaskMapper tasks,
            KitchenTicketMapper tickets,
            OrderBatchMapper batches,
            OrderLineMapper lines,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        return new DiningKitchenReportFactsPortAdapter(
                days, events, tasks, tickets, batches, lines, manager);
    }
}
