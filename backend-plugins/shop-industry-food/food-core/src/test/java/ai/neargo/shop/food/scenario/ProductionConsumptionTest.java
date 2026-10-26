package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.food.dto.CartSelectionInput;
import ai.neargo.shop.food.dto.CartVO;
import ai.neargo.shop.food.dto.CatalogItemVO;
import ai.neargo.shop.food.dto.CatalogMenuVO;
import ai.neargo.shop.food.dto.CatalogSkuVO;
import ai.neargo.shop.food.dto.CatalogVO;
import ai.neargo.shop.food.dto.DiningPriceFacts;
import ai.neargo.shop.food.dto.DishVO;
import ai.neargo.shop.food.dto.KitchenContracts.FrozenRoute;
import ai.neargo.shop.food.dto.KitchenContracts.FrozenRoutes;
import ai.neargo.shop.food.dto.MeasurementPolicy;
import ai.neargo.shop.food.dto.OptionGroupConfigVO;
import ai.neargo.shop.food.dto.OrderLineInput;
import ai.neargo.shop.food.dto.PublicationSnapshot;
import ai.neargo.shop.food.dto.PublicationSnapshot.Dependency;
import ai.neargo.shop.food.dto.RecipeAdjustmentVO;
import ai.neargo.shop.food.dto.RecipeLineVO;
import ai.neargo.shop.food.dto.RecipeOptionGroupSnapshot;
import ai.neargo.shop.food.dto.RecipePublicationSnapshot;
import ai.neargo.shop.food.dto.RecipeVO;
import ai.neargo.shop.food.entity.FnbKitchenTask;
import ai.neargo.shop.food.entity.FnbKitchenTicket;
import ai.neargo.shop.food.entity.FnbOrderBatch;
import ai.neargo.shop.food.entity.FnbOrderLine;
import ai.neargo.shop.food.entity.FnbProductionConsumption;
import ai.neargo.shop.food.entity.FnbProductionConsumptionLine;
import ai.neargo.shop.food.entity.FnbServiceEvent;
import ai.neargo.shop.food.mapper.FoodMappers.ConfigRevisionMapper;
import ai.neargo.shop.food.mapper.FoodMappers.KitchenTaskMapper;
import ai.neargo.shop.food.mapper.FoodMappers.KitchenTicketMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderBatchMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderLineMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ProductionConsumptionLineMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ProductionConsumptionMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ServiceEventMapper;
import ai.neargo.shop.food.policy.DiningPriceSourcePolicy;
import ai.neargo.shop.food.policy.ProductionDemandPolicy;
import ai.neargo.shop.food.service.impl.ProductionConsumptionServiceImpl;
import ai.neargo.shop.spi.inventory.InventoryProductionPort;
import ai.neargo.shop.spi.inventory.InventoryProductionPort.Command;
import ai.neargo.shop.spi.inventory.InventoryProductionPort.Line;
import ai.neargo.shop.spi.inventory.InventoryProductionPort.Receipt;
import ai.neargo.shop.spi.product.DishOfferQueryPort.Offer;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.SkuIdentityFact;
import ai.neargo.shop.spi.user.StoreCardQueryPort;
import ai.neargo.shop.spi.user.StoreDirectoryPort.StoreCard;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

class ProductionConsumptionTest {
    private static final String HASH = "a".repeat(64);

    private OrderLineInput selection() {
        return new OrderLineInput(
                "LINE",
                "MENU",
                "ITEM",
                "MR",
                "GOODS",
                "SKU",
                BigDecimal.ONE,
                null,
                null,
                List.of(),
                List.of());
    }

    private RecipePublicationSnapshot recipe(String net, String loss) {
        return recipe("2", net, loss);
    }

    private RecipePublicationSnapshot recipe(String yield, String net, String loss) {
        var recipe =
                new RecipeVO(
                        "RECIPE",
                        "S",
                        "SKU",
                        "DEFAULT",
                        new BigDecimal(yield),
                        "EA",
                        "ACTIVE",
                        1,
                        1,
                        List.of(
                                new RecipeLineVO(
                                        "RL",
                                        "MATERIAL",
                                        new BigDecimal(net),
                                        "EA",
                                        new BigDecimal(loss),
                                        HASH)));
        return new RecipePublicationSnapshot(
                3, "RECIPE", "RECIPE", 1, recipe, List.of(), List.of(), List.of(), null);
    }

    @Test
    void derivesExactGrossQuantityFromFrozenYieldAndLoss() {
        var lines =
                ProductionDemandPolicy.evaluate(
                        recipe("3", "0.25"), selection(), new BigDecimal("2"));
        assertThat(lines).containsExactly(new Line("MATERIAL", new BigDecimal("4"), "EA", HASH));
    }

    @Test
    void preservesExactFractionalDemandAndRoundsNonterminatingDemandUpToInventoryScale() {
        // Terminating demand is never rounded.
        assertThat(ProductionDemandPolicy.evaluate(recipe("1", "0"), selection(), BigDecimal.ONE))
                .containsExactly(new Line("MATERIAL", new BigDecimal("0.5"), "EA", HASH));
        // gross = net * started / (yield * (1 - lossRate)), rounded UP once at the inventory
        // exact-quantity scale (6): stock is never under-consumed and no order fails on division.
        assertThat(
                        ProductionDemandPolicy.evaluate(
                                recipe("1", "0.7"), selection(), BigDecimal.ONE))
                .containsExactly(new Line("MATERIAL", new BigDecimal("1.666667"), "EA", HASH));
        // 1 / 2.7 = 0.3703703...: HALF_UP would give 0.370370 and under-consume.
        assertThat(
                        ProductionDemandPolicy.evaluate(
                                recipe("3", "1", "0.1"), selection(), BigDecimal.ONE))
                .containsExactly(new Line("MATERIAL", new BigDecimal("0.370371"), "EA", HASH));
        // Terminating but finer than the inventory scale: 0.000001 / 2 = 0.0000005 -> 0.000001.
        assertThat(
                        ProductionDemandPolicy.evaluate(
                                recipe("0.000001", "0"), selection(), BigDecimal.ONE))
                .containsExactly(new Line("MATERIAL", new BigDecimal("0.000001"), "EA", HASH));
        // Common parameters all produce a demand line.
        for (var loss : List.of("0.1", "0.05", "0.02", "0.3"))
            for (var yield : List.of("3", "6", "7"))
                assertThat(
                                ProductionDemandPolicy.evaluate(
                                        recipe(yield, "1", loss), selection(), BigDecimal.ONE))
                        .singleElement()
                        .satisfies(
                                l ->
                                        assertThat(l.quantity().stripTrailingZeros().scale())
                                                .isLessThanOrEqualTo(6));
    }

    @Test
    void publishedTasteSelectionWithoutRecipeBindingDoesNotChangeMaterialDemand() {
        var taste =
                new OrderLineInput(
                        "LINE",
                        "MENU",
                        "ITEM",
                        "MR",
                        "GOODS",
                        "SKU",
                        BigDecimal.ONE,
                        null,
                        null,
                        List.of(
                                new CartSelectionInput(
                                        "SPICE", "SPEC_VALUE", "HOT", BigDecimal.ONE, 8L)),
                        List.of());
        assertThat(
                        ProductionDemandPolicy.evaluateComponent(
                                recipe("1", "0"), taste, BigDecimal.ONE))
                .containsExactly(new Line("MATERIAL", new BigDecimal("0.5"), "EA", HASH));
    }

    @Test
    void appliesUniqueSetBeforeAddAndRejectsRemoveConflict() {
        var optionItems =
                List.of(
                        new OptionGroupConfigVO.Item(
                                "O1",
                                "SPEC_VALUE",
                                "V1",
                                null,
                                BigDecimal.ZERO,
                                BigDecimal.ONE,
                                BigDecimal.ZERO,
                                1),
                        new OptionGroupConfigVO.Item(
                                "O2",
                                "SPEC_VALUE",
                                "V2",
                                null,
                                BigDecimal.ZERO,
                                BigDecimal.ONE,
                                BigDecimal.ZERO,
                                2));
        var group =
                new OptionGroupConfigVO(
                        "G",
                        "OPTION_GROUP",
                        1,
                        "ACTIVE",
                        new OptionGroupConfigVO.Data(
                                "Taste",
                                BigDecimal.ZERO,
                                new BigDecimal("2"),
                                null,
                                Map.of(),
                                optionItems));
        var doc =
                new PublicationSnapshot(
                        1,
                        "OPTION_GROUP",
                        "G",
                        1,
                        null,
                        group,
                        List.of(),
                        List.of(),
                        List.of(),
                        null);
        var groupSnapshot =
                new RecipeOptionGroupSnapshot(
                        "GOODS", new Dependency("GR", "OPTION_GROUP", "G", 1, HASH), doc);
        var selected =
                new OrderLineInput(
                        "LINE",
                        "MENU",
                        "ITEM",
                        "MR",
                        "GOODS",
                        "SKU",
                        BigDecimal.ONE,
                        null,
                        null,
                        List.of(
                                new CartSelectionInput("G", "SPEC_VALUE", "V1", BigDecimal.ONE, 1L),
                                new CartSelectionInput(
                                        "G", "SPEC_VALUE", "V2", BigDecimal.ONE, 1L)),
                        List.of());
        var base = recipe("1", "0");
        var adjustments =
                List.of(
                        new RecipeAdjustmentVO(
                                "A1", "O1", "RL", "SET", new BigDecimal("2"), "EA", HASH),
                        new RecipeAdjustmentVO(
                                "A2", "O2", "RL", "ADD", new BigDecimal("2"), "EA", HASH));
        var r = base.recipe();
        var adjusted =
                new RecipeVO(
                        r.recipeNo(),
                        r.storeNo(),
                        r.skuNo(),
                        r.recipeCode(),
                        r.yieldQty(),
                        r.yieldUnit(),
                        r.status(),
                        r.version(),
                        r.configVersion(),
                        r.lines(),
                        adjustments);
        var snapshot =
                new RecipePublicationSnapshot(
                        3,
                        "RECIPE",
                        "RECIPE",
                        1,
                        adjusted,
                        List.of(),
                        List.of(),
                        List.of(groupSnapshot),
                        null);
        assertThat(ProductionDemandPolicy.evaluate(snapshot, selected, BigDecimal.ONE))
                .containsExactly(new Line("MATERIAL", new BigDecimal("2"), "EA", HASH));
        var stale =
                new OrderLineInput(
                        "LINE",
                        "MENU",
                        "ITEM",
                        "MR",
                        "GOODS",
                        "SKU",
                        BigDecimal.ONE,
                        null,
                        null,
                        List.of(
                                new CartSelectionInput(
                                        "G", "SPEC_VALUE", "V1", BigDecimal.ONE, 2L)),
                        List.of());
        assertThatThrownBy(
                        () ->
                                ProductionDemandPolicy.evaluateComponent(
                                        snapshot, stale, BigDecimal.ONE))
                .isInstanceOf(BizException.class);
        var unknown =
                new OrderLineInput(
                        "LINE",
                        "MENU",
                        "ITEM",
                        "MR",
                        "GOODS",
                        "SKU",
                        BigDecimal.ONE,
                        null,
                        null,
                        List.of(
                                new CartSelectionInput(
                                        "G", "SPEC_VALUE", "UNKNOWN", BigDecimal.ONE, 1L)),
                        List.of());
        assertThatThrownBy(
                        () ->
                                ProductionDemandPolicy.evaluateComponent(
                                        snapshot, unknown, BigDecimal.ONE))
                .isInstanceOf(BizException.class);
        var conflictRecipe =
                new RecipeVO(
                        r.recipeNo(),
                        r.storeNo(),
                        r.skuNo(),
                        r.recipeCode(),
                        r.yieldQty(),
                        r.yieldUnit(),
                        r.status(),
                        r.version(),
                        r.configVersion(),
                        r.lines(),
                        List.of(
                                new RecipeAdjustmentVO(
                                        "A1", "O1", "RL", "REMOVE", null, "EA", HASH),
                                adjustments.getLast()));
        var conflicting =
                new RecipePublicationSnapshot(
                        3,
                        "RECIPE",
                        "RECIPE",
                        1,
                        conflictRecipe,
                        List.of(),
                        List.of(),
                        List.of(groupSnapshot),
                        null);
        assertThatThrownBy(
                        () ->
                                ProductionDemandPolicy.evaluate(
                                        conflicting, selected, BigDecimal.ONE))
                .isInstanceOfSatisfying(
                        BizException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    void finishedSkuIntentStaysTheoreticalWithoutAnyRawStockRpc() {
        for (String state : List.of("PREPARED", "UNKNOWN", "BLOCKED")) {
            var owner = mock(InventoryProductionPort.class);
            var fixture = new Fixture(owner);
            fixture.head.setStatus(state);
            var result = fixture.service.consume("E", "S", "A", "EVENT");
            assertThat(result.status()).isEqualTo("BLOCKED");
            assertThat(result.quantityEvidence()).isEqualTo("THEORETICAL_ONLY");
            assertThat(result.consumptionPending()).isTrue();
            assertThat(result.inventoryOperationRef()).isNull();
            assertThat(result.lines().getFirst().theoryQty()).isEqualByComparingTo("2");
            assertThat(result.lines().getFirst().inventoryIssuedQty()).isNull();
            assertThat(result.totalCostMinor()).isNull();
            assertThat(result.costCurrency()).isNull();
            verifyNoInteractions(owner);
        }
    }

    @Test
    void originalConfirmedInventoryEvidenceRemainsReadableWithoutNewRpc() {
        var owner = mock(InventoryProductionPort.class);
        var fixture = new Fixture(owner);
        fixture.head.setStatus("CONFIRMED");
        fixture.head.setInventoryOperationRef("ORIGINAL_DOC");
        var json = new ObjectMapper();
        var command = json.readValue(fixture.head.getRequestSnapshot(), Command.class);
        fixture.head.setReceiptSnapshot(
                json.writeValueAsString(
                        new Receipt(
                                command.eventNo(),
                                ProductionConsumptionServiceImpl.fingerprint(command),
                                "CONFIRMED",
                                "ORIGINAL_DOC",
                                command.lines(),
                                null,
                                null)));
        fixture.child.setActualQty(new BigDecimal("2"));
        fixture.child.setInventoryReceiptRef("ORIGINAL_DOC");
        fixture.child.setStatus("CONFIRMED");
        var result = fixture.service.consume("E", "S", "A", "EVENT");
        assertThat(result.status()).isEqualTo("CONFIRMED");
        assertThat(result.consumptionPending()).isFalse();
        assertThat(result.quantityEvidence()).isEqualTo("INVENTORY_ISSUE_ONLY");
        assertThat(result.inventoryOperationRef()).isEqualTo("ORIGINAL_DOC");
        verifyNoInteractions(owner);
    }

    @Test
    void strictReceiptGuardRejectsUnmatchedMaterialQuantities() {
        var fixture = new Fixture(mock(InventoryProductionPort.class));
        var command =
                new ObjectMapper().readValue(fixture.head.getRequestSnapshot(), Command.class);
        var invalid =
                new Receipt(
                        command.eventNo(),
                        ProductionConsumptionServiceImpl.fingerprint(command),
                        "CONFIRMED",
                        "DOC",
                        List.of(new Line("MATERIAL", new BigDecimal("99"), "EA", HASH)),
                        null,
                        null);
        assertThatThrownBy(() -> ProductionConsumptionServiceImpl.validate(command, invalid))
                .isInstanceOf(BizException.class);
    }

    @Test
    void validNoRecipeStartCreatesDurableBlockedReceiptInsteadOfPoisoningConsumer() {
        var fixture = new NewEventFixture();
        var first = fixture.service.consume("E", "S", "A", "EVENT");
        assertThat(first.status()).isEqualTo("BLOCKED");
        assertThat(first.quantityEvidence()).isEqualTo("THEORY_UNAVAILABLE:FROZEN_RECIPE_MISSING");
        assertThat(first.lines()).isEmpty();
        assertThat(first.consumptionPending()).isTrue();
        assertThat(first.inventoryOperationRef()).isNull();
        assertThat(first.totalCostMinor()).isNull();
        assertThat(fixture.stored.get().getRequestSnapshot())
                .contains("FROZEN_RECIPE_MISSING", "TASK", "EVENT");
        // Real consumer acknowledges a durable BLOCKED result; replay must return this same stored
        // intent.
        assertThat(fixture.service.consume("E", "S", "A", "EVENT").consumptionNo())
                .isEqualTo(first.consumptionNo());
        verify(fixture.heads).insert(any(FnbProductionConsumption.class));
        verifyNoInteractions(fixture.inventory, fixture.revisions);
    }

    @Test
    void validMultipleMakeRoutesRetainReasonWithoutInventingDuplicateMaterialLines() {
        var fixture = new NewEventFixture();
        fixture.batch.setFrozenRequest(
                fixture.json.writeValueAsString(
                        Map.of(
                                "facts",
                                fixture.facts("E"),
                                "kitchenRoutes",
                                new FrozenRoutes(
                                        List.of(
                                                new FrozenRoute(
                                                        "SKU", "STATION", "MAKE", 1, "SR", HASH),
                                                new FrozenRoute(
                                                        "SKU", "SECOND", "MAKE", 1, "SR2",
                                                        HASH))))));
        var receipt = fixture.service.consume("E", "S", "A", "EVENT");
        assertThat(receipt.status()).isEqualTo("BLOCKED");
        assertThat(receipt.quantityEvidence()).isEqualTo("THEORY_UNAVAILABLE:MULTIPLE_MAKE_ROUTES");
        assertThat(receipt.lines()).isEmpty();
        verifyNoInteractions(fixture.inventory, fixture.revisions);
    }

    @Test
    void damagedStoredSaleUnitCannotBeDowngradedToBlocked() {
        var fixture = new NewEventFixture();
        fixture.line.setUnit("KG");
        assertThatThrownBy(() -> fixture.service.consume("E", "S", "A", "EVENT"))
                .isInstanceOf(BizException.class);
        verify(fixture.heads, never()).insert(any(FnbProductionConsumption.class));
        verifyNoInteractions(fixture.inventory, fixture.revisions);
    }

    @Test
    void crossEntityFrozenEvidenceStillFailsBeforeBlockedProjectionOrStockCall() {
        var fixture = new NewEventFixture();
        var snapshot = fixture.json.readTree(fixture.batch.getFrozenRequest());
        var foreign = fixture.facts("OTHER");
        fixture.batch.setFrozenRequest(
                fixture.json.writeValueAsString(
                        Map.of("facts", foreign, "kitchenRoutes", snapshot.get("kitchenRoutes"))));
        assertThatThrownBy(() -> fixture.service.consume("E", "S", "A", "EVENT"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(fixture.heads, never()).insert(any(FnbProductionConsumption.class));
        verifyNoInteractions(fixture.inventory, fixture.revisions);
    }

    @Test
    void corruptedFrozenSourceHashIsNotSilentlyDowngradedToBlocked() {
        var fixture = new NewEventFixture();
        var facts = fixture.facts("E");
        var invalid = new DiningPriceFacts(facts.schemaVersion(), facts.payload(), "0".repeat(64));
        var snapshot = fixture.json.readTree(fixture.batch.getFrozenRequest());
        fixture.batch.setFrozenRequest(
                fixture.json.writeValueAsString(
                        Map.of("facts", invalid, "kitchenRoutes", snapshot.get("kitchenRoutes"))));
        assertThatThrownBy(() -> fixture.service.consume("E", "S", "A", "EVENT"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(fixture.heads, never()).insert(any(FnbProductionConsumption.class));
        verifyNoInteractions(fixture.inventory, fixture.revisions);
    }

    private static class NewEventFixture {
        final ObjectMapper json = new ObjectMapper();
        final InventoryProductionPort inventory = mock(InventoryProductionPort.class);
        final ConfigRevisionMapper revisions = mock(ConfigRevisionMapper.class);
        final ProductionConsumptionMapper heads = mock(ProductionConsumptionMapper.class);
        final AtomicReference<FnbProductionConsumption> stored = new AtomicReference<>();
        final FnbOrderBatch batch = new FnbOrderBatch();
        final FnbOrderLine line = new FnbOrderLine();
        final ProductionConsumptionServiceImpl service;

        NewEventFixture() {
            var children = mock(ProductionConsumptionLineMapper.class);
            when(heads.selectList(any()))
                    .thenAnswer(i -> stored.get() == null ? List.of() : List.of(stored.get()));
            when(heads.insert(any(FnbProductionConsumption.class)))
                    .thenAnswer(
                            i -> {
                                stored.set(i.getArgument(0));
                                return 1;
                            });
            when(children.selectList(any())).thenReturn(List.of());
            var events = mock(ServiceEventMapper.class);
            var event = new FnbServiceEvent();
            event.setEntityNo("E");
            event.setEventNo("EVENT");
            event.setTaskNo("TASK");
            event.setAction("START");
            event.setQty(BigDecimal.ONE);
            event.setCreatedAt(LocalDateTime.of(2026, 10, 24, 0, 0));
            when(events.selectOne(any())).thenReturn(event);
            var tasks = mock(KitchenTaskMapper.class);
            var task = new FnbKitchenTask();
            task.setEntityNo("E");
            task.setTaskNo("TASK");
            task.setTicketNo("TICKET");
            task.setFoodLineNo("FL");
            task.setStationNo("STATION");
            task.setRouteVersion(1L);
            task.setTaskRole("MAKE");
            task.setQty(BigDecimal.ONE);
            when(tasks.selectOne(any())).thenReturn(task);
            var tickets = mock(KitchenTicketMapper.class);
            var ticket = new FnbKitchenTicket();
            ticket.setEntityNo("E");
            ticket.setTicketNo("TICKET");
            ticket.setBatchNo("BATCH");
            ticket.setKind("MAKE");
            when(tickets.selectOne(any())).thenReturn(ticket);
            var batches = mock(OrderBatchMapper.class);
            batch.setEntityNo("E");
            batch.setStoreNo("S");
            batch.setBatchNo("BATCH");
            batch.setStatus("ACCEPTED");
            batch.setBusinessDate(LocalDate.of(2026, 10, 24));
            batch.setFrozenRequest(
                    json.writeValueAsString(
                            Map.of(
                                    "facts",
                                    facts("E"),
                                    "kitchenRoutes",
                                    new FrozenRoutes(
                                            List.of(
                                                    new FrozenRoute(
                                                            "SKU", "STATION", "MAKE", 1, "SR",
                                                            HASH))))));
            when(batches.selectOne(any())).thenReturn(batch);
            var orderLines = mock(OrderLineMapper.class);
            line.setEntityNo("E");
            line.setBatchNo("BATCH");
            line.setFoodLineNo("FL");
            line.setGoodsNo("GOODS");
            line.setSkuNo("SKU");
            line.setSourceLineRef("LINE");
            line.setQty(BigDecimal.ONE);
            line.setUnit("EA");
            when(orderLines.selectOne(any())).thenReturn(line);
            var stores = mock(StoreCardQueryPort.class);
            when(stores.cards(any()))
                    .thenReturn(
                            Map.of(
                                    "S",
                                    new StoreCard(
                                            "S", "Store", "E", null, "ACTIVE", true, null, null,
                                            null, null, 0, 0)));
            service =
                    new ProductionConsumptionServiceImpl(
                            heads,
                            children,
                            batches,
                            orderLines,
                            tickets,
                            tasks,
                            events,
                            revisions,
                            stores,
                            inventory,
                            json,
                            new Transactions());
        }

        DiningPriceFacts facts(String entity) {
            var selected =
                    new OrderLineInput(
                            "LINE",
                            "MENU",
                            "ITEM",
                            "MR",
                            "GOODS",
                            "SKU",
                            BigDecimal.ONE,
                            null,
                            null,
                            List.of(),
                            List.of());
            var cart =
                    new CartVO(
                            "CART",
                            "S",
                            null,
                            null,
                            null,
                            "OPEN",
                            "H5",
                            "PICKUP",
                            null,
                            1L,
                            List.of(selected));
            var menu = new CatalogMenuVO("MENU", "MR", 1L, "Menu", 0, null, List.of());
            var item =
                    new CatalogItemVO(
                            "MENU",
                            "ITEM",
                            "CATEGORY",
                            "Category",
                            "GOODS",
                            "Dish",
                            null,
                            new DishVO(
                                    "GOODS",
                                    "SINGLE",
                                    0,
                                    null,
                                    new MeasurementPolicy("EA", "COUNT", 0, null),
                                    0,
                                    0),
                            List.of(),
                            List.of(
                                    new CatalogSkuVO(
                                            "SKU", null, null, 100L, "CNY", "KNOWN", 10, "KNOWN",
                                            true, false, null, true, false, null)));
            var catalog = new CatalogVO("S", "H5", "PICKUP", null, List.of(menu), List.of(item));
            var offer =
                    new Offer("SKU", "GOODS", entity, "S", 100L, "CNY", "KNOWN", 10, "KNOWN", true);
            var units = DiningPriceSourcePolicy.compose(cart, catalog, Map.of("SKU", offer), json);
            var payload =
                    new DiningPriceFacts.Payload(
                            entity,
                            null,
                            cart,
                            List.of(new DiningPriceFacts.MenuRef("MENU", "MR", 1, HASH, List.of())),
                            List.of(new SkuIdentityFact("SKU", "GOODS", entity, "S", 0)),
                            List.of(offer),
                            List.of(
                                    new DiningPriceFacts.Restriction(
                                            "SKU", false, null, true, List.of())),
                            units,
                            new DiningPriceFacts.Composition(List.of(menu), List.of(item)),
                            "CNY",
                            100L);
            return new DiningPriceFacts(1, payload, PricingSnapshotHash.of(payload));
        }
    }

    private static class Fixture {
        final ProductionConsumptionMapper heads = mock(ProductionConsumptionMapper.class);
        final ProductionConsumptionLineMapper lines = mock(ProductionConsumptionLineMapper.class);
        final FnbProductionConsumption head = new FnbProductionConsumption();
        final FnbProductionConsumptionLine child = new FnbProductionConsumptionLine();
        final ProductionConsumptionServiceImpl service;

        Fixture(InventoryProductionPort inventory) {
            var json = new ObjectMapper();
            var command =
                    new Command(
                            "E",
                            "S",
                            "EVENT",
                            "PRODUCTION",
                            Instant.parse("2026-10-24T00:00:00Z"),
                            List.of(new Line("MATERIAL", new BigDecimal("2"), "EA", HASH)));
            head.setEntityNo("E");
            head.setStoreNo("S");
            head.setConsumptionNo("C");
            head.setSourceEventNo("EVENT");
            head.setPurpose("PRODUCTION");
            head.setStatus("PREPARED");
            head.setVersion(0L);
            head.setRequestSnapshot(json.writeValueAsString(command));
            head.setRequestHash(ProductionConsumptionServiceImpl.fingerprint(command));
            head.setBusinessDate(LocalDate.of(2026, 10, 24));
            child.setLineNo(1);
            child.setItemRef("MATERIAL");
            child.setTheoryQty(new BigDecimal("2"));
            child.setUnit("EA");
            child.setStatus("PENDING");
            when(heads.selectList(any())).thenReturn(List.of(head));
            when(heads.updateById(any(FnbProductionConsumption.class))).thenReturn(1);
            when(lines.selectList(any())).thenReturn(List.of(child));
            when(lines.updateById(any(FnbProductionConsumptionLine.class))).thenReturn(1);
            var stores = mock(StoreCardQueryPort.class);
            when(stores.cards(any()))
                    .thenReturn(
                            Map.of(
                                    "S",
                                    new StoreCard(
                                            "S", "Store", "E", null, "ACTIVE", true, null, null,
                                            null, null, 0, 0)));
            service =
                    new ProductionConsumptionServiceImpl(
                            heads,
                            lines,
                            mock(OrderBatchMapper.class),
                            mock(OrderLineMapper.class),
                            mock(KitchenTicketMapper.class),
                            mock(KitchenTaskMapper.class),
                            mock(ServiceEventMapper.class),
                            mock(ConfigRevisionMapper.class),
                            stores,
                            inventory,
                            json,
                            new Transactions());
        }
    }

    private static class Transactions extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {}

        @Override
        protected void doCommit(DefaultTransactionStatus status) {}

        @Override
        protected void doRollback(DefaultTransactionStatus status) {}
    }
}
