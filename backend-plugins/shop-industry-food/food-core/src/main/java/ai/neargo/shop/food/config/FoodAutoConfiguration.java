package ai.neargo.shop.food.config;

import ai.neargo.shop.food.service.CatalogCurrencyService;
import ai.neargo.shop.food.service.impl.CatalogCurrencyServiceImpl;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.food.service.StoreCalendarSourceService;
import ai.neargo.shop.food.service.impl.StoreCalendarSourceServiceImpl;
import ai.neargo.shop.food.service.MenuCatalogPriceService;
import ai.neargo.shop.food.service.impl.MenuCatalogPriceServiceImpl;
import ai.neargo.shop.food.port.LazyGoodsCatalogQueryPortImpl;
import ai.neargo.shop.food.port.LazyMaterialCatalogQueryPortImpl;
import ai.neargo.shop.food.port.LazyGoodsMetadataQueryPortImpl;
import ai.neargo.shop.food.service.KitchenTaskService;
import ai.neargo.shop.food.mapper.FoodMappers.ActionLogMapper;
import ai.neargo.shop.food.mapper.ReservationTableMapper;
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
import ai.neargo.shop.food.policy.RecipePublicationSource;
import ai.neargo.shop.food.service.BundleService;
import ai.neargo.shop.food.service.DiningBatchQueryService;
import ai.neargo.shop.food.service.impl.DiningBatchQueryServiceImpl;
import ai.neargo.shop.food.mapper.DiningBatchQueryMapper;
import ai.neargo.shop.food.service.BusinessShareService;
import ai.neargo.shop.food.service.BusinessReturnService;
import ai.neargo.shop.food.service.impl.BusinessReturnServiceImpl;
import ai.neargo.shop.food.service.impl.BusinessShareServiceImpl;
import ai.neargo.shop.food.mapper.BusinessSharePlanMapper;
import ai.neargo.shop.food.service.CheckService;
import ai.neargo.shop.food.service.CheckCartService;
import ai.neargo.shop.food.service.DiningPricingService;
import ai.neargo.shop.food.service.impl.DiningPricingServiceImpl;
import ai.neargo.shop.spi.trade.PricingCheckoutPort;
import ai.neargo.shop.spi.trade.IndustryOrderPort;
import ai.neargo.shop.spi.trade.IndustryAfterSalePort;
import ai.neargo.shop.spi.trade.IndustryCheckoutPort;
import ai.neargo.shop.food.service.DiningCheckoutService;
import ai.neargo.shop.food.service.DiningTransactionRecoveryService;
import ai.neargo.shop.food.service.impl.DiningTransactionRecoveryServiceImpl;
import ai.neargo.shop.food.service.impl.DiningCheckoutServiceImpl;
import ai.neargo.shop.food.mapper.FoodMappers.SettlementMapper;
import ai.neargo.shop.food.mapper.FoodMappers.SettlementItemMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ChargeIntentMapper;
import ai.neargo.shop.food.service.DiningReturnService;
import ai.neargo.shop.food.service.impl.DiningReturnServiceImpl;
import ai.neargo.shop.food.mapper.FoodMappers.ReturnMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ReturnItemMapper;
import ai.neargo.shop.food.service.DiningOrderService;
import ai.neargo.shop.food.service.SalesQuotaClaimService;
import ai.neargo.shop.food.service.impl.DiningOrderServiceImpl;
import ai.neargo.shop.food.service.impl.SalesQuotaClaimServiceImpl;
import ai.neargo.shop.food.mapper.FoodMappers.OrderBatchMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CoreOrderBindingMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderLineMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderComponentMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderModifierMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CoreLineAllocationMapper;
import ai.neargo.shop.food.mapper.FoodMappers.SalesQuotaClaimMapper;
import ai.neargo.shop.food.service.DiningPricingFactsService;
import ai.neargo.shop.food.service.impl.DiningPricingFactsServiceImpl;
import ai.neargo.shop.food.service.impl.CheckCartServiceImpl;
import ai.neargo.shop.food.mapper.FoodMappers.CheckCartMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CheckCartItemMapper;
import ai.neargo.shop.food.service.ConfigRevisionService;
import ai.neargo.shop.food.service.DiningSessionService;
import ai.neargo.shop.food.service.DishService;
import ai.neargo.shop.food.service.ModifierService;
import ai.neargo.shop.food.service.OptionGroupService;
import ai.neargo.shop.food.service.RecipeService;
import ai.neargo.shop.spi.media.MediaAssetQueryPort;
import ai.neargo.shop.food.service.CatalogService;
import ai.neargo.shop.food.service.impl.CatalogServiceImpl;
import ai.neargo.shop.spi.product.DishOfferQueryPort;
import ai.neargo.shop.spi.user.StoreCardQueryPort;
import ai.neargo.shop.food.service.MenuChannelBindingService;
import ai.neargo.shop.food.service.impl.MenuChannelBindingServiceImpl;
import ai.neargo.shop.food.service.StationService;
import ai.neargo.shop.food.service.SalesRestrictionService;
import ai.neargo.shop.food.service.impl.StationServiceImpl;
import ai.neargo.shop.food.service.impl.SalesRestrictionServiceImpl;
import ai.neargo.shop.food.mapper.FoodMappers.StationMapper;
import ai.neargo.shop.food.mapper.FoodMappers.SkuStationMapper;
import ai.neargo.shop.food.mapper.FoodMappers.SalesRestrictionMapper;
import ai.neargo.shop.food.service.MenuService;
import ai.neargo.shop.food.service.impl.MenuServiceImpl;
import ai.neargo.shop.food.policy.MenuPublicationSource;
import ai.neargo.shop.food.policy.StationPublicationSource;
import ai.neargo.shop.food.policy.MenuWorkingDefinitionAccess;
import ai.neargo.shop.food.dto.MenuConfigResultVO;
import ai.neargo.shop.food.entity.FnbConfigRevision;
import ai.neargo.shop.food.policy.MenuCompositionSource;
import ai.neargo.shop.food.mapper.FoodMappers.MenuMapper;
import ai.neargo.shop.food.mapper.FoodMappers.MenuItemOptionGroupMapper;
import ai.neargo.shop.food.mapper.FoodMappers.MenuItemSkuMapper;
import ai.neargo.shop.food.mapper.FoodMappers.MenuItemMapper;
import ai.neargo.shop.food.mapper.FoodMappers.MenuContextMapper;
import ai.neargo.shop.food.mapper.FoodMappers.MenuChannelBindingMapper;
import ai.neargo.shop.food.spi.MenuChannelAuthorityPort;
import ai.neargo.shop.food.mapper.FoodMappers.MenuCategoryMapper;
import ai.neargo.shop.food.mapper.FoodMappers.MenuScheduleMapper;
import ai.neargo.shop.food.mapper.FoodMappers.MenuExceptionMapper;
import ai.neargo.shop.food.mapper.FoodMappers.MenuStoreMapper;
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
@Import({FoodPersistenceConfiguration.class, FoodWriteGuardConfiguration.class, FoodOperationsConfiguration.class})
@ConditionalOnFoodEnabled
@EnableConfigurationProperties(FoodProperties.class)
public class FoodAutoConfiguration {
    @Bean
    @Conditional(FoodPersistenceConfiguration.PersistenceEnabled.class)
    @ConditionalOnMissingBean(CatalogCurrencyService.class)
    CatalogCurrencyService catalogCurrencyService(ObjectProvider<GoodsCatalogQueryPort> goods,
            ObjectProvider<CatalogPriceSourcePort> markets,
            @Value("${shop.food.commerce.catalog-currency.enabled:false}") boolean enabled) {
        return new CatalogCurrencyServiceImpl(new LazyGoodsCatalogQueryPortImpl(goods), markets::getIfAvailable,
                enabled, Clock.systemUTC());
    }

    @Bean
    @Conditional(FoodPersistenceConfiguration.PersistenceEnabled.class)
    @ConditionalOnMissingBean(StoreCalendarSourceService.class)
    StoreCalendarSourceService storeCalendarSourceService(
            StorePolicyMapper policies, ServiceCalendarMapper calendars,
            ObjectProvider<StoreCardQueryPort> stores, ObjectMapper json,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        return new StoreCalendarSourceServiceImpl(
                policies, calendars, stores::getIfAvailable, json, manager, Clock.systemUTC());
    }

    @Bean
    @Conditional(FoodPersistenceConfiguration.PersistenceEnabled.class)
    @ConditionalOnMissingBean(MenuCatalogPriceService.class)
    MenuCatalogPriceService menuCatalogPriceService(ConfigRevisionService revisions,
            ObjectProvider<CatalogPriceSourcePort> prices, ObjectMapper json) {
        return new MenuCatalogPriceServiceImpl(revisions, prices::getIfAvailable, json, Clock.systemUTC());
    }

    private static GoodsCatalogQueryPort lazyGoods(ObjectProvider<GoodsCatalogQueryPort> provider) {
        return new LazyGoodsCatalogQueryPortImpl(provider);
    }

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
            @Value("${shop.food.bundle-publication-enabled:false}") boolean bundlePublicationEnabled,
            RecipeMapper recipeHeads, RecipeLineMapper recipeLines, RecipeAdjustmentMapper recipeAdjustments,
            DishOptionGroupMapper recipeBindings, ObjectProvider<MaterialCatalogQueryPort> materialCatalog,
            @Value("${shop.food.recipe-publication-enabled:false}") boolean recipePublicationEnabled,
            ObjectProvider<MenuWorkingDefinitionAccess> workingMenus, DishMapper dishes,
            MenuChannelBindingMapper channelBindings, StationMapper stationHeads, SkuStationMapper stationRoutes,
            ObjectProvider<MenuChannelAuthorityPort> channelAuthority,
            @Value("${shop.food.menu-publication-enabled:false}") boolean menuPublicationEnabled,
            @Value("${shop.food.station-publication-enabled:false}") boolean stationPublicationEnabled,
            CatalogCurrencyService currencies) {
        GoodsCatalogQueryPort catalog = new LazyGoodsCatalogQueryPortImpl(goods);
        return new ConfigRevisionServiceImpl(revisions, modifiers, components, groups, items, actions, idem,
                publisher, json, manager, catalog, (entity, numbers) -> specs.getObject().getValues(entity, numbers),
                Clock.systemUTC(), new BundlePublicationSource(bundles, bundleGroups, bundleChoices,
                        () -> bundlePublicationEnabled),
                new RecipePublicationSource(recipeHeads, recipeLines, recipeAdjustments, items, groups,
                        recipeBindings, () -> recipePublicationEnabled, json),
                new LazyMaterialCatalogQueryPortImpl(materialCatalog),
                menuPublicationEnabled ? new MenuPublicationSource(new MenuWorkingDefinitionAccess() {
                    @Override
                    public MenuConfigResultVO capture(String entity, String store, String menu, boolean lock) {
                        return workingMenus.getObject().capture(entity, store, menu, lock);
                    }
                    @Override public long currentVersion(String entity, String store, String menu, boolean lock) {
                        return workingMenus.getObject().currentVersion(entity, store, menu, lock);
                    }
                    @Override public void advance(String entity, String store, String menu, long version,
                            FnbConfigRevision revision) {
                        workingMenus.getObject().advance(entity, store, menu, version, revision);
                    }
                }, dishes, recipeBindings, groups, bundles, recipeHeads, channelBindings, json) : null,
                stationPublicationEnabled
                        ? new StationPublicationSource(stationHeads, stationRoutes, () -> true) : null,
                channelAuthority::getIfAvailable, channelBindings, currencies);
    }

    @Bean
    @Conditional(FoodPersistenceConfiguration.PersistenceEnabled.class)
    @ConditionalOnMissingBean(CatalogService.class)
    CatalogService catalogService(MenuMapper menus, MenuStoreMapper bindings, ServiceCalendarMapper calendars,
            GuestSessionMapper sessions, ConfigRevisionService revisions, StoreOperationService operation,
            SalesRestrictionService restrictions, ObjectProvider<StoreCardQueryPort> stores,
            ObjectProvider<GoodsCatalogQueryPort> goods, ObjectProvider<DishOfferQueryPort> offers, ObjectMapper json,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        GoodsCatalogQueryPort catalog = new LazyGoodsMetadataQueryPortImpl(goods);
        return new CatalogServiceImpl(menus, bindings, calendars, sessions, revisions, operation, restrictions,
                numbers -> stores.getObject().cards(numbers), catalog,
                (entity, store, numbers) -> offers.getObject().getOffers(entity, store, numbers),
                json, manager, Clock.systemUTC());
    }

    @Bean
    @Conditional(FoodPersistenceConfiguration.PersistenceEnabled.class)
    @ConditionalOnMissingBean(StationService.class)
    StationService stationService(StationMapper stations, SkuStationMapper routes, ActionLogMapper actions,
            IdempotencyService idem, @Qualifier("foodOutboxPublisher") OutboxPublisher publisher, ObjectMapper json,
            @Qualifier("transactionManager") PlatformTransactionManager manager,
            ObjectProvider<GoodsCatalogQueryPort> goods) {
        return new StationServiceImpl(stations, routes, actions, idem, publisher, json, manager, lazyGoods(goods));
    }

    @Bean
    @Conditional(FoodPersistenceConfiguration.PersistenceEnabled.class)
    @ConditionalOnMissingBean(SalesRestrictionService.class)
    SalesRestrictionService salesRestrictionService(SalesRestrictionMapper rows, StorePolicyMapper policies,
            ActionLogMapper actions, IdempotencyService idem,
            @Qualifier("foodOutboxPublisher") OutboxPublisher publisher, ObjectMapper json,
            @Qualifier("transactionManager") PlatformTransactionManager manager,
            ObjectProvider<GoodsCatalogQueryPort> goods) {
        return new SalesRestrictionServiceImpl(rows, policies, actions, idem, publisher, json, manager,
                Clock.systemUTC(), lazyGoods(goods));
    }

    @Bean
    @Conditional(FoodPersistenceConfiguration.PersistenceEnabled.class)
    @ConditionalOnMissingBean(MenuChannelBindingService.class)
    MenuChannelBindingService menuChannelBindingService(MenuChannelBindingMapper bindings, MenuMapper menus,
            MenuStoreMapper stores, ActionLogMapper actions, IdempotencyService idem,
            @Qualifier("foodOutboxPublisher") OutboxPublisher publisher, ObjectMapper json,
            @Qualifier("transactionManager") PlatformTransactionManager manager,
            ObjectProvider<MenuChannelAuthorityPort> authority) {
        return new MenuChannelBindingServiceImpl(bindings, menus, stores, actions, idem, publisher, json, manager,
                authority::getIfAvailable);
    }

    @Bean
    @Conditional(FoodPersistenceConfiguration.PersistenceEnabled.class)
    @ConditionalOnMissingBean(MenuService.class)
    MenuServiceImpl menuService(MenuMapper heads, MenuStoreMapper bindings, StorePolicyMapper policies,
            MenuScheduleMapper weeks, MenuExceptionMapper exceptions,
            MenuCategoryMapper categories, MenuItemMapper items, MenuItemSkuMapper skus,
            MenuItemOptionGroupMapper itemGroups, ObjectProvider<GoodsCatalogQueryPort> goods,
            MenuContextMapper contexts, MenuChannelBindingMapper channels,
            ObjectProvider<MenuChannelAuthorityPort> channelAuthority,
            ActionLogMapper actions, IdempotencyService idem,
            @Qualifier("foodOutboxPublisher") OutboxPublisher publisher, ObjectMapper json,
            @Qualifier("transactionManager") PlatformTransactionManager manager,
            CatalogCurrencyService currencies) {
        return new MenuServiceImpl(heads, bindings, policies, actions, idem, publisher, json, manager,
                Clock.systemUTC(), weeks, exceptions, new MenuCompositionSource(categories, items, skus, itemGroups,
                        new LazyGoodsMetadataQueryPortImpl(goods)), contexts, channels,
                channelAuthority::getIfAvailable, currencies);
    }

    @Bean
    @Conditional(FoodPersistenceConfiguration.PersistenceEnabled.class)
    @ConditionalOnMissingBean(RecipeService.class)
    RecipeService recipeService(RecipeMapper heads, RecipeLineMapper lines, ActionLogMapper actions,
            RecipeAdjustmentMapper adjustments, OptionGroupItemMapper optionItems, OptionGroupMapper optionGroups,
            IdempotencyService idem, @Qualifier("foodOutboxPublisher") OutboxPublisher publisher,
            ObjectMapper json, @Qualifier("transactionManager") PlatformTransactionManager manager,
            ObjectProvider<GoodsCatalogQueryPort> catalogs, ObjectProvider<MaterialCatalogQueryPort> materials,
            CatalogCurrencyService currencies) {
        GoodsCatalogQueryPort catalog = new LazyGoodsCatalogQueryPortImpl(catalogs);
        return new RecipeServiceImpl(heads, lines, actions, idem, publisher, json, manager, catalog,
                new LazyMaterialCatalogQueryPortImpl(materials),
                adjustments, optionItems, optionGroups, currencies);
    }

    @Bean
    @Conditional(FoodPersistenceConfiguration.PersistenceEnabled.class)
    @ConditionalOnMissingBean(BundleService.class)
    BundleService bundleService(BundleMapper heads, BundleGroupMapper groups, BundleChoiceMapper choices,
            ActionLogMapper actions, IdempotencyService idem,
            @Qualifier("foodOutboxPublisher") OutboxPublisher publisher, ObjectMapper json,
            @Qualifier("transactionManager") PlatformTransactionManager manager,
            ObjectProvider<GoodsCatalogQueryPort> catalogs,
            CatalogCurrencyService currencies) {
        GoodsCatalogQueryPort catalog = new LazyGoodsCatalogQueryPortImpl(catalogs);
        return new BundleServiceImpl(heads, groups, choices, actions, idem, publisher, json, manager,
                catalog, currencies);
    }

    @Bean
    @Conditional(FoodPersistenceConfiguration.PersistenceEnabled.class)
    @ConditionalOnMissingBean(ModifierService.class)
    ModifierService modifierService(ModifierMapper heads, ModifierComponentMapper components,
            ActionLogMapper actions, IdempotencyService idem,
            @Qualifier("foodOutboxPublisher") OutboxPublisher publisher, ObjectMapper json,
            @Qualifier("transactionManager") PlatformTransactionManager manager,
            ObjectProvider<GoodsCatalogQueryPort> catalogs,
            CatalogCurrencyService currencies) {
        GoodsCatalogQueryPort catalog = new LazyGoodsCatalogQueryPortImpl(catalogs);
        return new ModifierServiceImpl(heads, components, actions, idem, publisher, json, manager, catalog, currencies);
    }

    @Bean
    @Conditional(FoodPersistenceConfiguration.PersistenceEnabled.class)
    @ConditionalOnMissingBean(OptionGroupService.class)
    OptionGroupService optionGroupService(OptionGroupMapper groups, OptionGroupItemMapper items,
            ActionLogMapper actions, IdempotencyService idem,
            @Qualifier("foodOutboxPublisher") OutboxPublisher publisher, ObjectMapper json,
            @Qualifier("transactionManager") PlatformTransactionManager manager, DishMapper dishes,
            DishOptionGroupMapper bindings, ModifierMapper modifiers, ObjectProvider<GoodsCatalogQueryPort> catalogs,
            ObjectProvider<SpecValueCatalogQueryPort> specs,
            CatalogCurrencyService currencies) {
        GoodsCatalogQueryPort catalog = new LazyGoodsMetadataQueryPortImpl(catalogs);
        return new OptionGroupServiceImpl(groups, items, actions, idem, publisher, json, manager,
                dishes, bindings, modifiers, catalog,
                (entity, numbers) -> specs.getObject().getValues(entity, numbers), currencies);
    }

    @Bean
    @Conditional(FoodPersistenceConfiguration.PersistenceEnabled.class)
    @ConditionalOnMissingBean(DishService.class)
    DishService dishService(DishMapper dishes, ActionLogMapper actions, IdempotencyService idem,
            @Qualifier("foodOutboxPublisher") OutboxPublisher publisher, ObjectMapper json,
            @Qualifier("transactionManager") PlatformTransactionManager manager,
            ObjectProvider<GoodsCatalogQueryPort> catalogs, DishOptionGroupMapper bindings,
            OptionGroupMapper groups, OptionGroupItemMapper items, ObjectProvider<MediaAssetQueryPort> media,
            CatalogCurrencyService currencies) {
        // Resolve on invocation so optional food-off/remote-client assembly never substitutes catalog facts.
        GoodsCatalogQueryPort catalog = new LazyGoodsMetadataQueryPortImpl(catalogs);
        return new DishServiceImpl(dishes, actions, idem, publisher, json, manager, catalog, bindings, groups, items,
                (entity, store, keys) -> media.getObject().getAssets(entity, store, keys), currencies);
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
            IdempotencyService idem, ReservationTableMapper reservationTables,
            @Qualifier("foodOutboxPublisher") OutboxPublisher publisher,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        return new TableServiceImpl(areas, tables, actions, idem, publisher, manager, reservationTables);
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
    @ConditionalOnMissingBean(DiningTransactionRecoveryService.class)
    DiningTransactionRecoveryService diningTransactionRecoveryService(OrderBatchMapper batches,
            SettlementMapper settlements, ReturnMapper returns, DiningOrderService orders,
            DiningCheckoutService checkout, DiningReturnService afterSales,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        return new DiningTransactionRecoveryServiceImpl(batches, settlements, returns, orders, checkout,
                afterSales, Clock.systemUTC(), manager);
    }

    @Bean
    @Conditional(FoodPersistenceConfiguration.PersistenceEnabled.class)
    @ConditionalOnMissingBean(DiningCheckoutService.class)
    DiningCheckoutService diningCheckoutService(CheckCartService carts, OrderBatchMapper batches,
            CoreOrderBindingMapper bindings, SettlementMapper settlements, SettlementItemMapper items,
            ChargeIntentMapper intents, SalesQuotaClaimService quotas, ObjectProvider<IndustryCheckoutPort> core,
            @Qualifier("foodOutboxPublisher") OutboxPublisher publisher, ObjectMapper json,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        return new DiningCheckoutServiceImpl(carts, batches, bindings, settlements, items, intents, quotas,
                core::getIfUnique, publisher, json, Clock.systemUTC(), manager);
    }

    @Bean
    @Conditional(FoodPersistenceConfiguration.PersistenceEnabled.class)
    @ConditionalOnMissingBean(DiningReturnService.class)
    DiningReturnService diningReturnService(CheckCartService carts, OrderBatchMapper batches,
            CoreOrderBindingMapper bindings, OrderLineMapper lines, CoreLineAllocationMapper allocations,
            ReturnMapper returns, ReturnItemMapper items, ObjectProvider<IndustryAfterSalePort> core,
            ObjectMapper json, @Qualifier("transactionManager") PlatformTransactionManager manager) {
        return new DiningReturnServiceImpl(carts, batches, bindings, lines, allocations, returns, items,
                core::getIfUnique, json, Clock.systemUTC(), manager);
    }

    @Bean
    @Conditional(FoodPersistenceConfiguration.PersistenceEnabled.class)
    @ConditionalOnMissingBean(SalesQuotaClaimService.class)
    SalesQuotaClaimService salesQuotaClaimService(SalesRestrictionMapper restrictions, StorePolicyMapper policies,
            SalesQuotaClaimMapper claims) {
        return new SalesQuotaClaimServiceImpl(restrictions, policies, claims, Clock.systemUTC());
    }

    @Bean
    @Conditional(FoodPersistenceConfiguration.PersistenceEnabled.class)
    @ConditionalOnMissingBean(DiningOrderService.class)
    DiningOrderService diningOrderService(CheckCartService carts, DiningPricingFactsService facts,
            StoreOperationService policies, SalesQuotaClaimService quotas, CheckCartMapper cartRows,
            StorePolicyMapper policyRows, OrderBatchMapper batches, CoreOrderBindingMapper bindings,
            OrderLineMapper lines, CoreLineAllocationMapper allocations, OrderComponentMapper components,
            OrderModifierMapper modifiers, ActionLogMapper actions,
            ObjectProvider<IndustryOrderPort> orders,
            ObjectProvider<PricingCheckoutPort> pricing, ObjectMapper json, @Qualifier("transactionManager") PlatformTransactionManager manager,
            KitchenTaskService kitchen, @Qualifier("foodOutboxPublisher") OutboxPublisher publisher) {
        return new DiningOrderServiceImpl(carts, facts, policies, quotas, cartRows, policyRows, batches,
                bindings, lines, allocations, orders::getIfUnique, pricing::getIfUnique, json, Clock.systemUTC(), manager,
                kitchen, publisher, components, modifiers, actions);
    }

    @Bean
    @Conditional(FoodPersistenceConfiguration.PersistenceEnabled.class)
    @ConditionalOnMissingBean(DiningPricingService.class)
    DiningPricingService diningPricingService(CheckCartService carts, ObjectProvider<PricingCheckoutPort> pricing) {
        return new DiningPricingServiceImpl(carts, pricing::getIfUnique);
    }

    @Bean
    @Conditional(FoodPersistenceConfiguration.PersistenceEnabled.class)
    @ConditionalOnMissingBean(DiningPricingFactsService.class)
    DiningPricingFactsService diningPricingFactsService(CheckCartService carts, CatalogService catalog,
            ConfigRevisionService revisions, SalesRestrictionService restrictions,
            ObjectProvider<DishOfferQueryPort> offers, ObjectProvider<StoreCardQueryPort> stores, ObjectMapper json) {
        return new DiningPricingFactsServiceImpl(carts, catalog, revisions,
                (entity, store, numbers) -> offers.getObject().getOffers(entity, store, numbers), restrictions,
                numbers -> stores.getObject().cards(numbers), json);
    }

    @Bean
    @Conditional(FoodPersistenceConfiguration.PersistenceEnabled.class)
    @ConditionalOnMissingBean(CheckCartService.class)
    CheckCartService checkCartService(CheckCartMapper carts, CheckCartItemMapper items, CheckMapper checks,
            GuestSessionMapper sessions, SeatMapper seats, TableOccupancyMapper occupancies,
            TableAreaMapper areas, TableMapper tables, ActionLogMapper actions, IdempotencyService idem,
            @Qualifier("foodOutboxPublisher") OutboxPublisher publisher, CatalogService catalog,
            ObjectProvider<StoreCardQueryPort> stores, ObjectMapper json,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        return new CheckCartServiceImpl(carts,items,checks,sessions,seats,occupancies,areas,tables,actions,idem,
                publisher,catalog,numbers -> stores.getObject().cards(numbers),json,manager,Clock.systemUTC());
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
            @Qualifier("transactionManager") PlatformTransactionManager manager, ReservationTableMapper reservationTables) {
        return new CheckServiceImpl(checks,areas,tables,occupancy,seats,operations,
                sessions,actions,policies,idem,publisher,json,manager,reservationTables);
    }

    @Bean
    @Conditional(FoodPersistenceConfiguration.PersistenceEnabled.class)
    @ConditionalOnMissingBean(ReservationService.class)
    ReservationService reservationService(ReservationMapper mapper, ReservationTableMapper reservationTables,
            TableAreaMapper areas, TableMapper tables, TableOccupancyMapper occupancy, ActionLogMapper actions,
            CheckService checks, StoreOperationService policies, IdempotencyService idem, ObjectMapper json,
            ObjectProvider<StoreCardQueryPort> stores, @Qualifier("foodOutboxPublisher") OutboxPublisher publisher,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        return new ReservationServiceImpl(mapper, reservationTables, areas, tables, occupancy, actions,
                checks, policies, idem, json, numbers -> stores.getObject().cards(numbers), publisher, manager);
    }

    @Bean
    @Conditional(FoodPersistenceConfiguration.PersistenceEnabled.class)
    @ConditionalOnMissingBean(DiningBatchQueryService.class)
    DiningBatchQueryService diningBatchQueryService(DiningBatchQueryMapper mapper,
            ObjectProvider<StoreCardQueryPort> stores,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        return new DiningBatchQueryServiceImpl(mapper, numbers -> stores.getObject().cards(numbers), manager);
    }
    @Bean
    @Conditional(FoodPersistenceConfiguration.PersistenceEnabled.class)
    @ConditionalOnMissingBean(BusinessReturnService.class)
    BusinessReturnService businessReturnService(OrderBatchMapper batches, CoreOrderBindingMapper bindings,
            OrderLineMapper lines, CoreLineAllocationMapper allocations, OrderComponentMapper components,
            ReturnMapper returns, ReturnItemMapper items, ActionLogMapper actions,
            ObjectProvider<IndustryOrderPort> orders, ObjectMapper json,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        return new BusinessReturnServiceImpl(batches, bindings, lines, allocations, components, returns, items,
                actions, orders::getIfAvailable, json, Clock.systemUTC(), manager);
    }

    @Bean
    @Conditional(FoodPersistenceConfiguration.PersistenceEnabled.class)
    @ConditionalOnMissingBean(BusinessShareService.class)
    BusinessShareService businessShareService(BusinessSharePlanMapper plans, CheckMapper checks,
            OrderBatchMapper batches, OrderLineMapper lines, CoreOrderBindingMapper bindings,
            CoreLineAllocationMapper allocations, IdempotencyService idem, ObjectMapper json,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        return new BusinessShareServiceImpl(plans, checks, batches, lines, bindings, allocations, idem, json, manager);
    }

    @Bean
    @ConditionalOnProperty(prefix = "shop.food.migration", name = "mode", havingValue = "off")
    FoodSchemaReady foodSchemaOff() { return FoodSchemaReady.off(); }

}
