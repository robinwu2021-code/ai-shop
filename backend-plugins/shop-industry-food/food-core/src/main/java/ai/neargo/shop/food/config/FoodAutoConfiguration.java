package ai.neargo.shop.food.config;

import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.food.mapper.FoodMappers.ActionLogMapper;
import ai.neargo.shop.food.mapper.FoodMappers.BundleChoiceMapper;
import ai.neargo.shop.food.mapper.FoodMappers.BundleGroupMapper;
import ai.neargo.shop.food.mapper.FoodMappers.BundleMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CheckMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ConfigRevisionMapper;
import ai.neargo.shop.food.mapper.FoodMappers.DishMapper;
import ai.neargo.shop.food.mapper.FoodMappers.DishOptionGroupMapper;
import ai.neargo.shop.food.mapper.FoodMappers.GuestSessionMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ModifierComponentMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ModifierMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OptionGroupItemMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OptionGroupMapper;
import ai.neargo.shop.food.mapper.FoodMappers.RecipeAdjustmentMapper;
import ai.neargo.shop.food.mapper.FoodMappers.RecipeLineMapper;
import ai.neargo.shop.food.mapper.FoodMappers.RecipeMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ReservationMapper;
import ai.neargo.shop.food.mapper.FoodMappers.SeatMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ServiceCalendarMapper;
import ai.neargo.shop.food.mapper.FoodMappers.StorePolicyMapper;
import ai.neargo.shop.food.mapper.FoodMappers.TableAreaMapper;
import ai.neargo.shop.food.mapper.FoodMappers.TableMapper;
import ai.neargo.shop.food.mapper.FoodMappers.TableOccupancyMapper;
import ai.neargo.shop.food.mapper.FoodMappers.TableOperationMapper;
import ai.neargo.shop.food.mapper.FoodMappers.TableTokenMapper;
import ai.neargo.shop.food.policy.BundlePublicationSource;
import ai.neargo.shop.food.service.BundleService;
import ai.neargo.shop.food.service.CheckService;
import ai.neargo.shop.food.service.ConfigRevisionService;
import ai.neargo.shop.food.service.DiningSessionService;
import ai.neargo.shop.food.service.DishService;
import ai.neargo.shop.food.service.ModifierService;
import ai.neargo.shop.food.service.OptionGroupService;
import ai.neargo.shop.food.service.RecipeService;
import ai.neargo.shop.food.service.ReservationService;
import ai.neargo.shop.food.service.StoreOperationService;
import ai.neargo.shop.food.service.TableService;
import ai.neargo.shop.food.service.impl.BundleServiceImpl;
import ai.neargo.shop.food.service.impl.CheckServiceImpl;
import ai.neargo.shop.food.service.impl.ConfigRevisionServiceImpl;
import ai.neargo.shop.food.service.impl.DiningSessionServiceImpl;
import ai.neargo.shop.food.service.impl.DishServiceImpl;
import ai.neargo.shop.food.service.impl.ModifierServiceImpl;
import ai.neargo.shop.food.service.impl.OptionGroupServiceImpl;
import ai.neargo.shop.food.service.impl.RecipeServiceImpl;
import ai.neargo.shop.food.service.impl.ReservationServiceImpl;
import ai.neargo.shop.food.service.impl.StoreOperationServiceImpl;
import ai.neargo.shop.food.service.impl.TableServiceImpl;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.spi.inventory.MaterialCatalogQueryPort;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort;
import ai.neargo.shop.spi.product.SpecValueCatalogQueryPort;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
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
    @ConditionalOnMissingBean(ConfigRevisionService.class)
    ConfigRevisionService configRevisionService(ConfigRevisionMapper revisions, ModifierMapper modifiers,
            ModifierComponentMapper components, OptionGroupMapper groups, OptionGroupItemMapper items,
            BundleMapper bundles, BundleGroupMapper bundleGroups, BundleChoiceMapper bundleChoices,
            ActionLogMapper actions, IdempotencyService idem,
            @Qualifier("foodOutboxPublisher") OutboxPublisher publisher, ObjectMapper json,
            @Qualifier("transactionManager") PlatformTransactionManager manager,
            ObjectProvider<GoodsCatalogQueryPort> goods, ObjectProvider<SpecValueCatalogQueryPort> specs,
            @Value("${shop.food.bundle-publication-enabled:false}") boolean bundlePublicationEnabled) {
        GoodsCatalogQueryPort catalog = new GoodsCatalogQueryPort() {
            @Override public SkuIdentityFact getSku(String entity, String store, String sku) {
                return goods.getObject().getSku(entity, store, sku);
            }
            @Override public GoodsFact getGoods(String entity, String store, String number) {
                return goods.getObject().getGoods(entity, store, number);
            }
            @Override public List<GoodsSummary> getGoodsBatch(String entity, String store, List<String> numbers) {
                return goods.getObject().getGoodsBatch(entity, store, numbers);
            }
        };
        return new ConfigRevisionServiceImpl(revisions, modifiers, components, groups, items, actions, idem,
                publisher, json, manager, catalog, (entity, numbers) -> specs.getObject().getValues(entity, numbers),
                Clock.systemUTC(), new BundlePublicationSource(bundles, bundleGroups, bundleChoices,
                        () -> bundlePublicationEnabled));
    }

    @Bean
    @Conditional(FoodPersistenceConfiguration.PersistenceEnabled.class)
    @ConditionalOnMissingBean(RecipeService.class)
    RecipeService recipeService(RecipeMapper heads, RecipeLineMapper lines, ActionLogMapper actions,
            RecipeAdjustmentMapper adjustments, OptionGroupItemMapper optionItems, OptionGroupMapper optionGroups,
            IdempotencyService idem, @Qualifier("foodOutboxPublisher") OutboxPublisher publisher,
            ObjectMapper json, @Qualifier("transactionManager") PlatformTransactionManager manager,
            ObjectProvider<GoodsCatalogQueryPort> catalogs, ObjectProvider<MaterialCatalogQueryPort> materials) {
        GoodsCatalogQueryPort catalog = new GoodsCatalogQueryPort() {
            @Override public SkuIdentityFact getSku(String entity, String store, String sku) {
                return catalogs.getObject().getSku(entity, store, sku);
            }
            @Override public GoodsFact getGoods(String entity, String store, String goods) {
                return catalogs.getObject().getGoods(entity, store, goods);
            }
            @Override public List<GoodsSummary> getGoodsBatch(String entity, String store, List<String> numbers) {
                return catalogs.getObject().getGoodsBatch(entity, store, numbers);
            }
        };
        return new RecipeServiceImpl(heads, lines, actions, idem, publisher, json, manager, catalog,
                (entity, ids) -> materials.getObject().getMaterials(entity, ids),
                adjustments, optionItems, optionGroups);
    }

    @Bean
    @Conditional(FoodPersistenceConfiguration.PersistenceEnabled.class)
    @ConditionalOnMissingBean(BundleService.class)
    BundleService bundleService(BundleMapper heads, BundleGroupMapper groups, BundleChoiceMapper choices,
            ActionLogMapper actions, IdempotencyService idem,
            @Qualifier("foodOutboxPublisher") OutboxPublisher publisher, ObjectMapper json,
            @Qualifier("transactionManager") PlatformTransactionManager manager,
            ObjectProvider<GoodsCatalogQueryPort> catalogs) {
        GoodsCatalogQueryPort catalog = new GoodsCatalogQueryPort() {
            @Override public SkuIdentityFact getSku(String entity, String store, String sku) {
                return catalogs.getObject().getSku(entity, store, sku);
            }
            @Override public GoodsFact getGoods(String entity, String store, String goods) {
                return catalogs.getObject().getGoods(entity, store, goods);
            }
            @Override public List<GoodsSummary> getGoodsBatch(String entity, String store, List<String> numbers) {
                return catalogs.getObject().getGoodsBatch(entity, store, numbers);
            }
        };
        return new BundleServiceImpl(heads, groups, choices, actions, idem, publisher, json, manager, catalog);
    }

    @Bean
    @Conditional(FoodPersistenceConfiguration.PersistenceEnabled.class)
    @ConditionalOnMissingBean(ModifierService.class)
    ModifierService modifierService(ModifierMapper heads, ModifierComponentMapper components,
            ActionLogMapper actions, IdempotencyService idem,
            @Qualifier("foodOutboxPublisher") OutboxPublisher publisher, ObjectMapper json,
            @Qualifier("transactionManager") PlatformTransactionManager manager,
            ObjectProvider<GoodsCatalogQueryPort> catalogs) {
        GoodsCatalogQueryPort catalog = new GoodsCatalogQueryPort() {
            @Override public SkuIdentityFact getSku(String entity, String store, String sku) {
                return catalogs.getObject().getSku(entity, store, sku);
            }
            @Override public GoodsFact getGoods(String entity, String store, String goods) {
                return catalogs.getObject().getGoods(entity, store, goods);
            }
            @Override public List<GoodsSummary> getGoodsBatch(String entity, String store, List<String> numbers) {
                return catalogs.getObject().getGoodsBatch(entity, store, numbers);
            }
        };
        return new ModifierServiceImpl(heads, components, actions, idem, publisher, json, manager, catalog);
    }

    @Bean
    @Conditional(FoodPersistenceConfiguration.PersistenceEnabled.class)
    @ConditionalOnMissingBean(OptionGroupService.class)
    OptionGroupService optionGroupService(OptionGroupMapper groups, OptionGroupItemMapper items,
            ActionLogMapper actions, IdempotencyService idem,
            @Qualifier("foodOutboxPublisher") OutboxPublisher publisher, ObjectMapper json,
            @Qualifier("transactionManager") PlatformTransactionManager manager, DishMapper dishes,
            DishOptionGroupMapper bindings, ModifierMapper modifiers, ObjectProvider<GoodsCatalogQueryPort> catalogs,
            ObjectProvider<SpecValueCatalogQueryPort> specs) {
        GoodsCatalogQueryPort catalog = new GoodsCatalogQueryPort() {
            @Override public GoodsFact getGoods(String entity, String store, String goods) {
                return catalogs.getObject().getGoods(entity, store, goods);
            }
            @Override public List<GoodsSummary> getGoodsBatch(String entity, String store, List<String> numbers) {
                return catalogs.getObject().getGoodsBatch(entity, store, numbers);
            }
        };
        return new OptionGroupServiceImpl(groups, items, actions, idem, publisher, json, manager,
                dishes, bindings, modifiers, catalog,
                (entity, numbers) -> specs.getObject().getValues(entity, numbers));
    }

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
