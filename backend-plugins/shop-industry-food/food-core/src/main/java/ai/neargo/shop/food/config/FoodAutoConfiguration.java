package ai.neargo.shop.food.config;

import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.food.mapper.FoodMappers.ActionLogMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ServiceCalendarMapper;
import ai.neargo.shop.food.mapper.FoodMappers.StorePolicyMapper;
import ai.neargo.shop.food.mapper.FoodMappers.TableAreaMapper;
import ai.neargo.shop.food.mapper.FoodMappers.TableMapper;
import ai.neargo.shop.food.service.StoreOperationService;
import ai.neargo.shop.food.service.TableService;
import ai.neargo.shop.food.service.impl.StoreOperationServiceImpl;
import ai.neargo.shop.food.service.impl.TableServiceImpl;
import ai.neargo.shop.idem.IdempotencyService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/** 餐饮模块装配入口。 */
@AutoConfiguration(afterName = {"org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
        "com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration"})
@Import({FoodPersistenceConfiguration.class, FoodWriteGuardConfiguration.class})
@ConditionalOnFoodEnabled
@EnableConfigurationProperties(FoodProperties.class)
public class FoodAutoConfiguration {
    @Bean
    @Conditional(FoodPersistenceConfiguration.PersistenceEnabled.class)
    @ConditionalOnMissingBean(StoreOperationService.class)
    StoreOperationService storeOperationService(StorePolicyMapper policies, ActionLogMapper actions,
            ServiceCalendarMapper calendars,
            IdempotencyService idempotency, @Qualifier("foodOutboxPublisher") OutboxPublisher publisher,
            ObjectMapper json, @Qualifier("transactionManager") PlatformTransactionManager manager) {
        return new StoreOperationServiceImpl(policies, actions, calendars, idempotency, publisher, json, manager);
    }

    @Bean
    @Conditional(FoodPersistenceConfiguration.PersistenceEnabled.class)
    @ConditionalOnMissingBean(TableService.class)
    TableService tableService(TableAreaMapper areas, TableMapper tables, ActionLogMapper actions,
            IdempotencyService idem,
            @Qualifier("foodOutboxPublisher") OutboxPublisher publisher,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        return new TableServiceImpl(areas, tables, actions, idem, publisher, manager);
    }

    @Bean
    @ConditionalOnProperty(prefix = "shop.food.migration", name = "mode", havingValue = "off")
    FoodSchemaReady foodSchemaOff() { return FoodSchemaReady.off(); }

}
