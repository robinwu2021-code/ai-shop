package ai.neargo.shop.product;

import static ai.neargo.shop.spi.product.LocalStockControlPort.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import ai.neargo.shop.product.port.FoodLocalStockFactsPortImpl;
import ai.neargo.shop.product.service.StockReplenishmentAdmissionService;
import ai.neargo.shop.product.service.impl.LocalStockControlServiceImpl;
import ai.neargo.shop.spi.food.FoodLocalStockPort;
import ai.neargo.shop.spi.product.LocalStockFactsPort;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class LocalStockControlServiceTest {
    private static final Scope SCOPE = new Scope("MAIN", "E", "S");
    static Request request(Industry industry, Strategy strategy, ItemKind kind, String qty, Action... actions) {
        return new Request(industry, strategy, kind, "ITEM", "EA", new BigDecimal(qty), Set.of(actions));
    }
    private static LocalStockControlServiceImpl service(List<LocalStockFactsPort> providers) {
        return new LocalStockControlServiceImpl(providers, replenishment(false));
    }
    private static StockReplenishmentAdmissionService replenishment(boolean enabled) {
        var admissions = mock(StockReplenishmentAdmissionService.class);
        when(admissions.connection(any())).thenReturn(new StockReplenishmentAdmissionService.Connection(
                enabled, enabled ? 1 : 0, 1, enabled ? "INV" : null, enabled ? "OWNER" : null, 0));
        return admissions;
    }
    private LocalStockFactsPort platform() {
        var source = mock(LocalStockFactsPort.class);
        when(source.owner()).thenReturn("PLATFORM");
        when(source.inspect(any(), any())).thenReturn(new LocalStockFactsPort.Facts(
                new Observation("ITEM", "EA", QuantityScope.STORE, "S", StockStatus.TRACKED,
                        new BigDecimal("5")), 0, Set.of(Action.QUERY, Action.RESERVE, Action.ISSUE_HANDOVER), List.of()));
        return source;
    }
    @Test void retailAndBeautyPhysicalSkuSelectRealPlatformWithoutInventoryProvider() {
        var platform = platform();
        var service = service(List.of(platform));
        for (var industry : List.of(Industry.RETAIL, Industry.BEAUTY)) {
            var result = service.preflight(SCOPE, request(industry, Strategy.LOCAL, ItemKind.SKU, "2", Action.RESERVE));
            assertThat(result.allowed()).isTrue();
            assertThat(result.route().owner()).isEqualTo("PLATFORM");
            assertThat(result.stock().availableQuantity()).isEqualByComparingTo("5");
        }
    }
    @Test void advancedStrategiesNeverQueryLocalOrFallBackToLocal() {
        var platform = platform();
        var service = service(List.of(platform));
        for (var strategy : List.of(Strategy.REPLENISH, Strategy.SHARED_SYNC, Strategy.DELEGATED)) {
            var result = service.preflight(SCOPE, request(Industry.RETAIL, strategy, ItemKind.SKU, "1", Action.RESERVE));
            assertThat(result.allowed()).isFalse();
            assertThat(result.issues()).containsExactly(Issue.STRATEGY_NOT_ENABLED);
        }
        for (var industry : List.of(Industry.FOOD, Industry.BEAUTY)) {
            var result = service.preflight(SCOPE, request(industry, Strategy.REPLENISH, ItemKind.SKU, "1", Action.RECEIVE));
            assertThat(result.route()).isNull();
            assertThat(result.issues()).containsExactly(Issue.STRATEGY_NOT_ENABLED);
        }
        verify(platform, never()).inspect(any(), any());
    }
    @Test void retailReplenishIsPublishedOnlyForAnEnabledStoreAndKeepsThePlatformPool() {
        var platform = platform();
        var service = new LocalStockControlServiceImpl(List.of(platform), replenishment(true));
        var result = service.preflight(SCOPE, request(Industry.RETAIL, Strategy.REPLENISH, ItemKind.SKU, "2",
                Action.RECEIVE, Action.ADJUST, Action.RESERVE));
        assertThat(result.allowed()).isTrue();
        assertThat(result.route()).isEqualTo(new Route(Industry.RETAIL, Strategy.REPLENISH, "PLATFORM"));
        assertThat(result.capabilities()).contains(Action.RECEIVE, Action.ADJUST, Action.RESERVE);
        assertThat(service.preflight(SCOPE, request(Industry.RETAIL, Strategy.REPLENISH, ItemKind.MATERIAL, "1",
                Action.RECEIVE)).issues()).containsExactly(Issue.STRATEGY_NOT_ENABLED);
        assertThat(service.preflight(new Scope("OTHER", "E", "S"), request(Industry.RETAIL, Strategy.REPLENISH,
                ItemKind.SKU, "1", Action.RECEIVE)).issues()).containsExactly(Issue.SCOPE_UNSUPPORTED);
        assertThat(service.preflight(SCOPE, request(Industry.RETAIL, Strategy.REPLENISH, ItemKind.SKU, "1.5",
                Action.RECEIVE)).issues()).contains(Issue.QUANTITY_PRECISION_UNSUPPORTED);
    }
    @Test void unsupportedAssetsAndBeautyServiceConsumptionCannotAdvertiseStockSupport() {
        var platform = platform();
        var service = service(List.of(platform));
        for (var kind : List.of(ItemKind.SERVICE_RIGHT, ItemKind.RESOURCE_CAPACITY)) {
            assertThat(service.preflight(SCOPE, request(Industry.BEAUTY, Strategy.LOCAL, kind, "1", Action.QUERY))
                    .issues()).containsExactly(Issue.NOT_PHYSICAL_STOCK);
        }
        var use = service.preflight(SCOPE, request(Industry.BEAUTY, Strategy.LOCAL, ItemKind.SKU, "1", Action.ISSUE_SERVICE_USE));
        assertThat(use.allowed()).isFalse();
        assertThat(use.issues()).contains(Issue.CAPABILITY_UNSUPPORTED);
    }
    @Test void integerLocalLedgerRejectsFractionsAndShortageWithoutClaimingReservation() {
        var service = service(List.of(platform()));
        assertThat(service.preflight(SCOPE, request(Industry.RETAIL, Strategy.LOCAL, ItemKind.SKU, "1.5", Action.RESERVE))
                .issues()).contains(Issue.QUANTITY_PRECISION_UNSUPPORTED);
        assertThat(service.preflight(SCOPE, request(Industry.RETAIL, Strategy.LOCAL, ItemKind.SKU, "6", Action.RESERVE))
                .issues()).contains(Issue.INSUFFICIENT_STOCK);
    }
    @Test void foodExactDecimalsUseFoodOwnerAndMissingComponentNeverUsesPlatform() {
        var beanFactory = new StaticListableBeanFactory();
        var food = mock(FoodLocalStockPort.class);
        beanFactory.addBean("food", food);
        when(food.findAvailability(any(), any())).thenReturn(List.of(new FoodLocalStockPort.Availability(
                "ITEM", "EA", "TRACKED", new BigDecimal("1.750000"))));
        var platform = platform();
        var service = service(List.of(platform,
                new FoodLocalStockFactsPortImpl(beanFactory.getBeanProvider(FoodLocalStockPort.class))));
        var result = service.preflight(SCOPE,
                request(Industry.FOOD, Strategy.LOCAL, ItemKind.MATERIAL, "0.125", Action.ISSUE_PRODUCTION));
        assertThat(result.allowed()).isTrue();
        assertThat(result.route().owner()).isEqualTo("FOOD_LOCAL");
        assertThat(result.stock().availableQuantity()).isEqualByComparingTo("1.75");
        verify(platform, never()).inspect(any(), any());
        verify(food).findAvailability(new FoodLocalStockPort.Scope("E", "S"),
                List.of(new FoodLocalStockPort.ItemRef("ITEM", "EA")));
        var empty = new StaticListableBeanFactory();
        var absent = service(List.of(platform,
                new FoodLocalStockFactsPortImpl(empty.getBeanProvider(FoodLocalStockPort.class))));
        assertThat(absent.preflight(SCOPE, request(Industry.FOOD, Strategy.LOCAL, ItemKind.MATERIAL, "1", Action.RESERVE))
                .issues()).containsExactly(Issue.EXECUTOR_UNAVAILABLE);
        verifyNoMoreInteractions(food);
    }
    @Test void duplicateExecutorAndUnknownFoodFactsFailClosed() {
        var a = platform(); var b = platform();
        var ambiguous = service(List.of(a, b));
        assertThat(ambiguous.preflight(SCOPE, request(Industry.RETAIL, Strategy.LOCAL, ItemKind.SKU, "1", Action.QUERY))
                .issues()).containsExactly(Issue.EXECUTOR_AMBIGUOUS);
        var factory = new StaticListableBeanFactory(); var food = mock(FoodLocalStockPort.class);
        factory.addBean("food", food);
        when(food.findAvailability(any(), any())).thenReturn(List.of(
                new FoodLocalStockPort.Availability("ITEM", "EA", "UNCONFIGURED", null)));
        var service = service(List.of(
                new FoodLocalStockFactsPortImpl(factory.getBeanProvider(FoodLocalStockPort.class))));
        var result = service.preflight(SCOPE, request(Industry.FOOD, Strategy.LOCAL, ItemKind.MATERIAL, "1", Action.RESERVE));
        assertThat(result.allowed()).isFalse(); assertThat(result.stock().availableQuantity()).isNull();
        assertThat(result.issues()).contains(Issue.ITEM_NOT_READY);
    }
    @Test void capabilityObservationNeverChangesLegacyFrozenOwnerPolicy() {
        assertThat(ai.neargo.shop.trade.stock.IndustryStockOwnerPolicy.frozenOwner(null, "INDUSTRY_INVENTORY_V2"))
                .isEqualTo("LEGACY_INVENTORY");
        assertThat(ai.neargo.shop.trade.stock.IndustryStockOwnerPolicy.newOwner("BEAUTY", true))
                .isEqualTo("LEGACY_INVENTORY");
    }
}
