package ai.neargo.shop.food.config;

import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.food.mapper.FoodMappers.ActionLogMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CheckMapper;
import ai.neargo.shop.food.mapper.FoodMappers.GuestSessionMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ReservationMapper;
import ai.neargo.shop.food.mapper.FoodMappers.SeatMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ServiceCalendarMapper;
import ai.neargo.shop.food.mapper.FoodMappers.StorePolicyMapper;
import ai.neargo.shop.food.mapper.FoodMappers.TableAreaMapper;
import ai.neargo.shop.food.mapper.FoodMappers.TableMapper;
import ai.neargo.shop.food.mapper.FoodMappers.TableOccupancyMapper;
import ai.neargo.shop.food.mapper.FoodMappers.TableOperationMapper;
import ai.neargo.shop.food.mapper.FoodMappers.TableTokenMapper;
import ai.neargo.shop.food.service.CheckService;
import ai.neargo.shop.food.service.DishService;
import ai.neargo.shop.food.service.impl.DishServiceImpl;
import ai.neargo.shop.food.mapper.FoodMappers.DishMapper;
import ai.neargo.shop.food.mapper.FoodMappers.DishOptionGroupMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OptionGroupMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OptionGroupItemMapper;
import java.util.List;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort;
import org.springframework.beans.factory.ObjectProvider;
import ai.neargo.shop.food.service.DiningSessionService;
import ai.neargo.shop.food.service.ReservationService;
import ai.neargo.shop.food.service.StoreOperationService;
import ai.neargo.shop.food.service.TableService;
import ai.neargo.shop.food.service.impl.CheckServiceImpl;
import ai.neargo.shop.food.service.impl.DiningSessionServiceImpl;
import ai.neargo.shop.food.service.impl.ReservationServiceImpl;
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
    @ConditionalOnMissingBean(DishService.class)
    DishService dishService(DishMapper dishes, ActionLogMapper actions, IdempotencyService idem,
            @Qualifier("foodOutboxPublisher") OutboxPublisher publisher, ObjectMapper json,
            @Qualifier("transactionManager") PlatformTransactionManager manager,
            ObjectProvider<GoodsCatalogQueryPort> catalogs, DishOptionGroupMapper bindings,
            OptionGroupMapper groups, OptionGroupItemMapper items) {
        // Resolve on invocation so optional food-off/remote-client assembly never substitutes catalog facts.
        GoodsCatalogQueryPort catalog = new GoodsCatalogQueryPort() {
            @Override public GoodsFact getGoods(String entity, String store, String goods) {
                return catalogs.getObject().getGoods(entity, store, goods);
            }
            @Override public List<GoodsSummary> getGoodsBatch(String entity, String store, List<String> numbers) {
                return catalogs.getObject().getGoodsBatch(entity, store, numbers);
            }
        };
        return new DishServiceImpl(dishes, actions, idem, publisher, json, manager, catalog, bindings, groups, items);
    }

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
    @Conditional(FoodPersistenceConfiguration.PersistenceEnabled.class)
    @ConditionalOnMissingBean(DiningSessionService.class)
    DiningSessionService diningSessionService(TableAreaMapper areas, TableMapper tables,
            TableTokenMapper tokens,
            GuestSessionMapper sessions, ActionLogMapper actions,
            IdempotencyService idem, @Qualifier("foodOutboxPublisher") OutboxPublisher publisher,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        return new DiningSessionServiceImpl(
                areas,tables,tokens,sessions,actions,idem,publisher,manager);
    }

    @Bean
    @Conditional(FoodPersistenceConfiguration.PersistenceEnabled.class)
    @ConditionalOnMissingBean(CheckService.class)
    CheckService checkService(
            CheckMapper checks,TableAreaMapper areas,TableMapper tables,
            TableOccupancyMapper occupancy,
            SeatMapper seats,
            TableOperationMapper operations,
            GuestSessionMapper sessions,ActionLogMapper actions,
            StoreOperationService policies,IdempotencyService idem,
            @Qualifier("foodOutboxPublisher") OutboxPublisher publisher,ObjectMapper json,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        return new CheckServiceImpl(checks,areas,tables,occupancy,seats,operations,
                sessions,actions,policies,idem,publisher,json,manager);
    }

    @Bean
    @Conditional(FoodPersistenceConfiguration.PersistenceEnabled.class)
    @ConditionalOnMissingBean(ReservationService.class)
    ReservationService reservationService(ReservationMapper mapper) { return new ReservationServiceImpl(mapper); }

    @Bean
    @ConditionalOnProperty(prefix = "shop.food.migration", name = "mode", havingValue = "off")
    FoodSchemaReady foodSchemaOff() { return FoodSchemaReady.off(); }

}
