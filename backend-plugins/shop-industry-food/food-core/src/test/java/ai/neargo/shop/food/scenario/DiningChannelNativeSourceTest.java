package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.food.dto.CartSelectionInput;
import ai.neargo.shop.food.dto.DiningChannelSourceFacts;
import ai.neargo.shop.food.dto.DiningChannelSourceFacts.Key;
import ai.neargo.shop.food.dto.KitchenContracts.FrozenRoutes;
import ai.neargo.shop.food.dto.StoreOperationPolicyResultVO;
import ai.neargo.shop.food.entity.FnbCheckCart;
import ai.neargo.shop.food.entity.FnbCheckCartItem;
import ai.neargo.shop.food.entity.FnbCoreLineAllocation;
import ai.neargo.shop.food.entity.FnbCoreOrderBinding;
import ai.neargo.shop.food.entity.FnbOrderBatch;
import ai.neargo.shop.food.entity.FnbOrderComponent;
import ai.neargo.shop.food.entity.FnbOrderLine;
import ai.neargo.shop.food.entity.FnbOrderModifier;
import ai.neargo.shop.food.entity.FnbSalesQuotaClaim;
import ai.neargo.shop.food.entity.FnbSalesRestriction;
import ai.neargo.shop.food.entity.FnbStorePolicy;
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
import ai.neargo.shop.food.mapper.FoodMappers.SalesQuotaClaimMapper;
import ai.neargo.shop.food.mapper.FoodMappers.SalesRestrictionMapper;
import ai.neargo.shop.food.mapper.FoodMappers.StorePolicyMapper;
import ai.neargo.shop.food.service.DiningChannelOrderService;
import ai.neargo.shop.food.service.KitchenTaskService;
import ai.neargo.shop.food.service.StoreOperationService;
import ai.neargo.shop.food.service.impl.DiningChannelOrderBinder;
import ai.neargo.shop.food.service.impl.DiningChannelOrderServiceImpl;
import ai.neargo.shop.food.service.impl.DiningChannelProductionPlanBuilder;
import ai.neargo.shop.food.service.impl.DiningChannelSourceStore;
import ai.neargo.shop.food.service.impl.SalesQuotaClaimServiceImpl;
import ai.neargo.shop.food.spi.DiningChannelSourceFactsPort;
import ai.neargo.shop.food.spi.DiningChannelSourceFactsPort.CurrentEvidence;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Plan;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.inventory.MaterialCatalogQueryPort;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Acceptance;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Allocation;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Preparation;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Reference;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Status;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.Channel;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.Collection;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.Delivery;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.Fulfillment;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.InvoiceLine;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.Totals;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;

import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Actual V26/27/28 source schema and FOOD transactions surround a controllable original-core RPC
 * boundary.
 */
class DiningChannelNativeSourceTest extends FoodWriteGuardFixture {
    static final Instant NOW = Instant.parse("2026-10-29T03:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static final String HASH = "d".repeat(64);
    static final Key KEY = new Key("E", "S", "IMPORT", HASH);

    record Context(
            Fixture fixture,
            DiningChannelOrderService service,
            DiningChannelSourceFactsPort source,
            IndustryChannelOrderPort core,
            AtomicReference<DiningChannelSourceFacts> evidence,
            AtomicBoolean failPublication,
            AtomicReference<Preparation> preparation) {}

    void run(Consumer<Context> test) {
        runner().run(
                        c -> {
                            var f = prepare(c);
                            var sql = c.getBean(SqlSessionTemplate.class);
                            for (var type :
                                    List.of(
                                            ChannelImportGuardMapper.class,
                                            ChannelOrderSourceMapper.class,
                                            ChannelPartyMapper.class,
                                            CheckCartMapper.class,
                                            CheckCartItemMapper.class,
                                            OrderBatchMapper.class,
                                            OrderLineMapper.class,
                                            OrderComponentMapper.class,
                                            OrderModifierMapper.class,
                                            CoreOrderBindingMapper.class,
                                            CoreLineAllocationMapper.class,
                                            SalesQuotaClaimMapper.class,
                                            SalesRestrictionMapper.class,
                                            StorePolicyMapper.class))
                                if (!sql.getConfiguration().hasMapper(type))
                                    sql.getConfiguration().addMapper(type);
                            for (var name :
                                    List.of(
                                            "V26__food_original_channel_sources.sql",
                                            "V27__food_channel_original_core_receipts.sql",
                                            "V28__food_channel_source_admission_guard.sql")) {
                                try {
                                    String ddl =
                                            new ClassPathResource("db/industry/food/" + name)
                                                    .getContentAsString(StandardCharsets.UTF_8);
                                    // H2 has no MySQL storage engine/character collation;
                                    // constraints and every source column remain original.
                                    ddl =
                                            ddl.replaceAll(
                                                    "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
                                                            + " COLLATE=utf8mb4_bin",
                                                    "");
                                    new ResourceDatabasePopulator(
                                                    new ByteArrayResource(
                                                            ddl.getBytes(StandardCharsets.UTF_8)))
                                            .execute(f.source());
                                } catch (IOException failure) {
                                    throw new IllegalStateException(failure);
                                }
                            }
                            for (var type :
                                    List.of(
                                            FnbCheckCart.class,
                                            FnbCheckCartItem.class,
                                            FnbOrderBatch.class,
                                            FnbOrderLine.class,
                                            FnbOrderComponent.class,
                                            FnbOrderModifier.class,
                                            FnbCoreOrderBinding.class,
                                            FnbCoreLineAllocation.class,
                                            FnbSalesQuotaClaim.class,
                                            FnbSalesRestriction.class,
                                            FnbStorePolicy.class)) create(f, type);
                            f.sql()
                                    .update(
                                            "INSERT INTO"
                                                + " fnb_store_policy(entity_no,policy_no,store_no,timezone,cutover_minute,effective_from)"
                                                + " VALUES('E','POLICY','S','UTC',0,?)",
                                            LocalDateTime.ofInstant(
                                                    NOW.minusSeconds(86400), ZoneOffset.UTC));
                            f.sql()
                                    .update(
                                            "INSERT INTO"
                                                + " fnb_sales_restriction(entity_no,restriction_no,store_no,sku_no,mode,quota_qty,"
                                                + "reserved_qty,consumed_qty,starts_at,status,order_source,fulfillment)"
                                                + " VALUES('E','QUOTA','S','SKU','QUOTA',100,0,0,?,'ACTIVE','CHANNEL','DELIVERY')",
                                            LocalDateTime.ofInstant(
                                                    NOW.minusSeconds(86400), ZoneOffset.UTC));
                            var quotas =
                                    new SalesQuotaClaimServiceImpl(
                                            sql.getMapper(SalesRestrictionMapper.class),
                                            sql.getMapper(StorePolicyMapper.class),
                                            sql.getMapper(SalesQuotaClaimMapper.class),
                                            CLOCK);
                            var json = c.getBean(ObjectMapper.class);
                            var store =
                                    new DiningChannelSourceStore(
                                            sql.getMapper(ChannelOrderSourceMapper.class),
                                            sql.getMapper(ChannelImportGuardMapper.class),
                                            sql.getMapper(ChannelPartyMapper.class),
                                            sql.getMapper(CheckCartMapper.class),
                                            sql.getMapper(CheckCartItemMapper.class),
                                            sql.getMapper(OrderBatchMapper.class),
                                            quotas,
                                            json,
                                            c.getBean(PlatformTransactionManager.class),
                                            CLOCK);
                            var owner = mock(DiningChannelSourceFactsPort.class);
                            var actual =
                                    new AtomicReference<>(
                                            facts("IMPORT", "ORDER", "CUSTOMER", false));
                            when(owner.read(any())).thenAnswer(a -> actual.get());
                            when(owner.current(any()))
                                    .thenAnswer(
                                            a -> {
                                                var source = actual.get();
                                                return new CurrentEvidence(
                                                        source.key(),
                                                        source.current(),
                                                        source.observedAt(),
                                                        source.validUntil(),
                                                        source.sourceReceiptRef());
                                            });
                            var policy = mock(StoreOperationService.class);
                            when(policy.getPolicy(anyString(), anyString(), any()))
                                    .thenReturn(
                                            new StoreOperationPolicyResultVO(
                                                    "POLICY",
                                                    "UTC",
                                                    0,
                                                    "PREPAY_ONLY",
                                                    List.of("DELIVERY"),
                                                    1));
                            var kitchen = mock(KitchenTaskService.class);
                            when(kitchen.freezeRoutes(anyString(), anyString(), anyList()))
                                    .thenReturn(new FrozenRoutes(List.of()));
                            var materials = mock(MaterialCatalogQueryPort.class);
                            var material =
                                    new MaterialCatalogQueryPort.MaterialFact(
                                            "MAT",
                                            "E",
                                            "Original goods",
                                            "PIECE",
                                            0,
                                            "OWN",
                                            "BASE_ONLY",
                                            "INTEGER_ONLY",
                                            MaterialCatalogFacts.definitionHash(
                                                    "MAT",
                                                    "E",
                                                    "Original goods",
                                                    "PIECE",
                                                    0,
                                                    "OWN"));
                            when(materials.getSkuMaterials("E", "S", List.of("SKU")))
                                    .thenReturn(
                                            List.of(
                                                    new MaterialCatalogQueryPort.SkuMaterialFact(
                                                            "SKU", material)));
                            var fail = new AtomicBoolean();
                            var publisher = mock(OutboxPublisher.class);
                            when(publisher.publish(any(), any()))
                                    .thenAnswer(
                                            a -> {
                                                assertThat(
                                                                TransactionSynchronizationManager
                                                                        .isActualTransactionActive())
                                                        .isTrue();
                                                if (fail.get())
                                                    throw new IllegalStateException(
                                                            "Outbox unavailable");
                                                return null;
                                            });
                            var binder =
                                    new DiningChannelOrderBinder(
                                            store,
                                            sql.getMapper(OrderLineMapper.class),
                                            sql.getMapper(OrderComponentMapper.class),
                                            sql.getMapper(OrderModifierMapper.class),
                                            sql.getMapper(CoreOrderBindingMapper.class),
                                            sql.getMapper(CoreLineAllocationMapper.class),
                                            publisher);
                            var core = mock(IndustryChannelOrderPort.class);
                            var service =
                                    new DiningChannelOrderServiceImpl(
                                            store,
                                            core,
                                            () -> List.of(owner),
                                            policy,
                                            kitchen,
                                            new DiningChannelProductionPlanBuilder(
                                                    store, materials, json),
                                            binder);
                            var prepared = new AtomicReference<Preparation>();
                            when(core.get(any()))
                                    .thenAnswer(
                                            a -> {
                                                var p = prepared.get();
                                                return p != null
                                                                && p.reference()
                                                                        .equals(a.getArgument(0))
                                                        ? p
                                                        : null;
                                            });
                            when(core.prepare(any()))
                                    .thenAnswer(
                                            a -> {
                                                assertThat(
                                                                TransactionSynchronizationManager
                                                                        .isActualTransactionActive())
                                                        .isFalse();
                                                Reference ref = a.getArgument(0);
                                                Plan plan =
                                                        service.demandPlan(
                                                                ref,
                                                                "ORIGINAL_RESERVATION",
                                                                "ORIGINAL_SUBORDER");
                                                var p =
                                                        new Preparation(
                                                                ref,
                                                                Status.PREPARED,
                                                                "ORIGINAL_CORE_ORDER",
                                                                "ORIGINAL_SUBORDER",
                                                                "ORIGINAL_PREPARATION_RECEIPT",
                                                                NOW.plusSeconds(20),
                                                                "READY",
                                                                plan,
                                                                "ORIGINAL_CORE_QUOTE");
                                                prepared.set(p);
                                                return p;
                                            });
                            when(core.confirm(any()))
                                    .thenAnswer(
                                            a -> {
                                                Reference ref = a.getArgument(0);
                                                var p = prepared.get();
                                                prepared.set(
                                                        new Preparation(
                                                                ref,
                                                                Status.ACCEPTED,
                                                                p.orderNo(),
                                                                p.subOrderNo(),
                                                                p.preparationReceiptRef(),
                                                                p.validUntil(),
                                                                "READY",
                                                                p.inventoryPlan(),
                                                                p.quoteNo()));
                                                var data = service.sourceFacts(ref).data();
                                                return new Acceptance(
                                                        ref,
                                                        p.orderNo(),
                                                        p.subOrderNo(),
                                                        "ORIGINAL_CORE_ACCEPTANCE",
                                                        "READY",
                                                        data.components().stream()
                                                                .map(
                                                                        component ->
                                                                                new Allocation(
                                                                                        component
                                                                                                .externalLineRef(),
                                                                                        component
                                                                                                .parentExternalLineRef(),
                                                                                        component
                                                                                                .externalKind(),
                                                                                        component
                                                                                                .sourceLineRef(),
                                                                                        component
                                                                                                .componentRef(),
                                                                                        "ORIGINAL_CORE_ITEM",
                                                                                        component
                                                                                                .physicalQuantity()
                                                                                                .toPlainString(),
                                                                                        component
                                                                                                .unit(),
                                                                                        component
                                                                                                .netMinor(),
                                                                                        data
                                                                                                .currency(),
                                                                                        "ORIGINAL_ALLOCATION_RECEIPT",
                                                                                        31))
                                                                .toList());
                                            });
                            test.accept(
                                    new Context(f, service, owner, core, actual, fail, prepared));
                        });
    }

    @Test
    void registersOwnedPartyCartAndSourceAndRecordsOnlyOriginalCoreQuote() {
        run(
                c -> {
                    var first = c.service().prepare(KEY);
                    var second = c.service().prepare(KEY);
                    assertThat(second.binding().reference()).isEqualTo(first.binding().reference());
                    var db = c.fixture().sql();
                    assertThat(
                                    db.queryForObject(
                                            "SELECT COUNT(*) FROM fnb_channel_party",
                                            Integer.class))
                            .isEqualTo(1);
                    assertThat(
                                    db.queryForObject(
                                            "SELECT COUNT(*) FROM fnb_check_cart", Integer.class))
                            .isEqualTo(1);
                    assertThat(
                                    db.queryForObject(
                                            "SELECT party_no FROM fnb_channel_party", String.class))
                            .startsWith("FCP");
                    assertThat(
                                    db.queryForObject(
                                            "SELECT quote_ref FROM fnb_order_batch", String.class))
                            .isEqualTo("ORIGINAL_CORE_QUOTE");
                    assertThat(
                                    db.queryForObject(
                                            "SELECT buyer_no FROM fnb_check_cart", String.class))
                            .isNull();
                    assertThat(
                                    db.queryForObject(
                                            "SELECT session_no FROM fnb_check_cart", String.class))
                            .isNull();
                    assertThat(first.binding().reference().sourceNo()).startsWith("FCS");
                    assertThat(
                                    first.corePreparation()
                                            .inventoryPlan()
                                            .branches()
                                            .getFirst()
                                            .leaves()
                                            .getFirst()
                                            .quantity())
                            .isEqualByComparingTo("2");
                });
    }

    @Test
    void priorCancellationPersistsGuardWithoutCreatingPartyCartBatchOrCallingCore() {
        run(
                c -> {
                    c.evidence()
                            .set(
                                    withState(
                                            c.evidence().get(),
                                            DiningChannelSourceFacts.State.CANCELLED));
                    var cancelled = c.service().cancel(KEY);
                    assertThat(c.service().prepare(KEY).binding().status()).isEqualTo("CANCELLED");
                    assertThat(c.service().cancel(KEY).cancellationReceiptRef())
                            .isEqualTo(cancelled.cancellationReceiptRef());
                    for (var table :
                            List.of(
                                    "fnb_channel_party",
                                    "fnb_check_cart",
                                    "fnb_order_batch",
                                    "fnb_channel_order_source"))
                        assertThat(
                                        c.fixture()
                                                .sql()
                                                .queryForObject(
                                                        "SELECT COUNT(*) FROM " + table,
                                                        Integer.class))
                                .isZero();
                    verify(c.core(), never()).prepare(any());
                });
    }

    @Test
    void acceptedCoreBindingAndOriginalSelectionsAreAtomicWithOutboxThenRecoverable() {
        run(
                c -> {
                    c.service().prepare(KEY);
                    c.evidence()
                            .set(
                                    withState(
                                            c.evidence().get(),
                                            DiningChannelSourceFacts.State.ACCEPTED));
                    c.failPublication().set(true);
                    assertThatThrownBy(() -> c.service().confirm(KEY))
                            .isInstanceOf(IllegalStateException.class);
                    for (var table :
                            List.of(
                                    "fnb_order_line",
                                    "fnb_order_component",
                                    "fnb_order_modifier",
                                    "fnb_core_line_allocation",
                                    "fnb_core_order_binding"))
                        assertThat(
                                        c.fixture()
                                                .sql()
                                                .queryForObject(
                                                        "SELECT COUNT(*) FROM " + table,
                                                        Integer.class))
                                .isZero();
                    assertThat(
                                    c.fixture()
                                            .sql()
                                            .queryForObject(
                                                    "SELECT status FROM fnb_order_batch",
                                                    String.class))
                            .isEqualTo("PREPARED");
                    assertThat(
                                    c.fixture()
                                            .sql()
                                            .queryForObject(
                                                    "SELECT reserved_qty FROM"
                                                            + " fnb_sales_restriction",
                                                    BigDecimal.class))
                            .isEqualByComparingTo("2");
                    assertThat(
                                    c.fixture()
                                            .sql()
                                            .queryForObject(
                                                    "SELECT consumed_qty FROM"
                                                            + " fnb_sales_restriction",
                                                    BigDecimal.class))
                            .isEqualByComparingTo("0");
                    c.failPublication().set(false);
                    // Recovery waits for the real lease; a restart must not manufacture another
                    // immediate claim.
                    c.fixture()
                            .sql()
                            .update("UPDATE fnb_channel_order_source SET lease_until=NULL");
                    var accepted = c.service().confirm(KEY);
                    assertThat(accepted.acceptanceReceiptRef())
                            .isEqualTo("ORIGINAL_CORE_ACCEPTANCE");
                    assertThat(
                                    c.fixture()
                                            .sql()
                                            .queryForObject(
                                                    "SELECT order_item_id FROM"
                                                            + " fnb_core_line_allocation",
                                                    Long.class))
                            .isEqualTo(31);
                    assertThat(
                                    c.fixture()
                                            .sql()
                                            .queryForObject(
                                                    "SELECT source_no FROM fnb_order_modifier",
                                                    String.class))
                            .isEqualTo("NO_SALT");
                    assertThat(
                                    c.fixture()
                                            .sql()
                                            .queryForObject(
                                                    "SELECT core_amount_minor FROM"
                                                            + " fnb_core_line_allocation",
                                                    Long.class))
                            .isEqualTo(189);
                    assertThat(c.service().binding(KEY).status()).isEqualTo("ACCEPTED");
                    assertThat(
                                    c.fixture()
                                            .sql()
                                            .queryForObject(
                                                    "SELECT consumed_qty FROM"
                                                            + " fnb_sales_restriction",
                                                    BigDecimal.class))
                            .isEqualByComparingTo("2");
                });
    }

    @Test
    void mismatchedHashCannotReuseCancelledImportGuard() {
        run(
                c -> {
                    c.evidence()
                            .set(
                                    withState(
                                            c.evidence().get(),
                                            DiningChannelSourceFacts.State.CANCELLED));
                    c.service().cancel(KEY);
                    assertThatThrownBy(
                                    () ->
                                            c.service()
                                                    .prepare(
                                                            new Key(
                                                                    "E",
                                                                    "S",
                                                                    "IMPORT",
                                                                    "e".repeat(64))))
                            .isInstanceOf(BizException.class);
                });
    }

    @Test
    void scheduledOriginalOrderRequiresActualCapacityOwnerBeforeAnySourceOrCartCommits() {
        run(
                c -> {
                    c.evidence().set(facts("IMPORT", "ORDER", "CUSTOMER", true));
                    assertThatThrownBy(() -> c.service().prepare(KEY))
                            .isInstanceOf(BizException.class);
                    for (var table :
                            List.of(
                                    "fnb_channel_party",
                                    "fnb_check_cart",
                                    "fnb_order_batch",
                                    "fnb_channel_order_source"))
                        assertThat(
                                        c.fixture()
                                                .sql()
                                                .queryForObject(
                                                        "SELECT COUNT(*) FROM " + table,
                                                        Integer.class))
                                .isZero();
                    verify(c.core(), never()).prepare(any());
                });
    }

    @Test
    void sameOriginalCustomerReusesOnlyTheNativeOwnedChannelParty() {
        run(
                c -> {
                    c.service().prepare(KEY);
                    c.preparation().set(null);
                    c.evidence().set(facts("IMPORT2", "ORDER2", "CUSTOMER", false));
                    c.service().prepare(c.evidence().get().key());
                    assertThat(
                                    c.fixture()
                                            .sql()
                                            .queryForObject(
                                                    "SELECT COUNT(*) FROM fnb_channel_party",
                                                    Integer.class))
                            .isEqualTo(1);
                    assertThat(
                                    c.fixture()
                                            .sql()
                                            .queryForObject(
                                                    "SELECT COUNT(*) FROM fnb_check_cart",
                                                    Integer.class))
                            .isEqualTo(2);
                });
    }

    static DiningChannelSourceFacts facts(
            String importNo, String externalOrder, String customer, boolean scheduled) {
        var key = new Key("E", "S", importNo, HASH);
        var component =
                new DiningChannelSourceFacts.Component(
                        "MAIN",
                        "LINE",
                        null,
                        "MAIN",
                        "FINISHED_SKU",
                        "GOODS",
                        "SKU",
                        "Original goods",
                        null,
                        "Original spec",
                        new BigDecimal("2"),
                        "PIECE",
                        190,
                        10,
                        9,
                        189,
                        "ORIGINAL_ALLOCATION_BASIS",
                        null,
                        null,
                        null,
                        null,
                        null);
        var line =
                new DiningChannelSourceFacts.Line(
                        "LINE",
                        "MENU",
                        "ITEM",
                        "MENU_REVISION",
                        "GOODS",
                        "SKU",
                        new BigDecimal("2"),
                        "PIECE",
                        null,
                        List.of(
                                new CartSelectionInput(
                                        "TASTE_GROUP",
                                        "SPEC_VALUE",
                                        "NO_SALT",
                                        BigDecimal.ONE,
                                        1L)),
                        List.of(),
                        List.of(component));
        var original =
                new DiningChannelSourceFacts.Original(
                        new Channel(
                                "TEST",
                                "PROFILE",
                                "a".repeat(64),
                                "ACCOUNT",
                                "CHANNEL_STORE",
                                "AE"),
                        "CONNECTION",
                        "CONNECTION_REVISION",
                        "EXTERNAL_SNAPSHOT",
                        externalOrder,
                        HASH,
                        "f".repeat(64),
                        "ORIGINAL_READ",
                        "AED",
                        Collection.PLATFORM_COLLECTED,
                        "PAYMENT",
                        Fulfillment.CHANNEL_DELIVERY,
                        new DiningChannelSourceFacts.PartyDescription(customer, "Recipient"),
                        new Delivery("Recipient", "123", "Address", null),
                        NOW.minusSeconds(600),
                        scheduled ? NOW.plusSeconds(3600) : null,
                        List.of(
                                new InvoiceLine(
                                        "LINE", new BigDecimal("2"), "PIECE", 190, 10, 9, 189)),
                        new Totals(190, 10, 9, 0, 189, 0, 0, 0, 189),
                        List.of(line),
                        List.of(
                                new DiningChannelSourceFacts.Definition(
                                        "MENU",
                                        "MENU",
                                        "MENU_REVISION",
                                        1,
                                        PricingSnapshotHash.of("ORIGINAL_MENU_BYTES"),
                                        "{}")),
                        "c".repeat(64),
                        "ORIGINAL_ITEMS_RECEIPT");
        var current =
                new DiningChannelSourceFacts.Current(
                        externalOrder,
                        HASH,
                        "f".repeat(64),
                        DiningChannelSourceFacts.State.WAITING_ACCEPTANCE,
                        Collection.PLATFORM_COLLECTED,
                        189L,
                        "PAYMENT",
                        null,
                        "FRESH_READ",
                        "QUERY_OPERATION",
                        "QUERY_ATTEMPT");
        return new DiningChannelSourceFacts(
                key, original, current, NOW, NOW.plusSeconds(25), "FRESH_READ");
    }

    static DiningChannelSourceFacts withState(
            DiningChannelSourceFacts facts, DiningChannelSourceFacts.State state) {
        var c = facts.current();
        return new DiningChannelSourceFacts(
                facts.key(),
                facts.original(),
                new DiningChannelSourceFacts.Current(
                        c.externalOrderRef(),
                        c.documentHash(),
                        c.financialHash(),
                        state,
                        c.collection(),
                        c.collectedMinor(),
                        c.originalCollectionRef(),
                        c.originalAcceptedAt(),
                        c.originalReadReceiptRef(),
                        c.queryOperationNo(),
                        c.queryAttemptRef()),
                facts.observedAt(),
                facts.validUntil(),
                facts.sourceReceiptRef());
    }

    static void create(Fixture fixture, Class<?> type) {
        var info = TableInfoHelper.getTableInfo(type);
        String columns =
                info.getFieldList().stream()
                        .map(
                                field -> {
                                    Class<?> java = field.getPropertyType();
                                    String column = field.getColumn();
                                    String sql =
                                            java == Long.class
                                                    ? "BIGINT"
                                                    : java == Integer.class
                                                            ? "INT"
                                                            : java == BigDecimal.class
                                                                    ? "DECIMAL(18,6)"
                                                                    : java == LocalDateTime.class
                                                                            ? "DATETIME(3)"
                                                                            : java
                                                                                            == LocalDate
                                                                                                    .class
                                                                                    ? "DATE"
                                                                                    : "VARCHAR(100000)";
                                    if (Set.of("version", "deleted").contains(column))
                                        sql += " DEFAULT 0";
                                    if (column.equals("tenant_no")) sql += " DEFAULT 'MAIN'";
                                    return column + " " + sql;
                                })
                        .collect(Collectors.joining(","));
        fixture.sql()
                .execute(
                        "CREATE TABLE "
                                + info.getTableName()
                                + " (id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                                + columns
                                + ")");
    }
}
