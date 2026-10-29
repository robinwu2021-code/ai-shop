package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.food.dto.CartResultVO;
import ai.neargo.shop.food.dto.CartVO;
import ai.neargo.shop.food.dto.ConfigRevisionContentVO;
import ai.neargo.shop.food.dto.ConfigRevisionVO;
import ai.neargo.shop.food.dto.DishPresentation;
import ai.neargo.shop.food.dto.DishVO;
import ai.neargo.shop.food.dto.MeasurementPolicy;
import ai.neargo.shop.food.dto.MenuCategoryInput;
import ai.neargo.shop.food.dto.MenuCompositionVO;
import ai.neargo.shop.food.dto.MenuConfigResultVO;
import ai.neargo.shop.food.dto.MenuContextInput;
import ai.neargo.shop.food.dto.MenuItemInput;
import ai.neargo.shop.food.dto.MenuPublicationSnapshot;
import ai.neargo.shop.food.dto.MenuScheduleVO;
import ai.neargo.shop.food.dto.MenuSourceContracts;
import ai.neargo.shop.food.dto.MenuVO;
import ai.neargo.shop.food.dto.OrderLineInput;
import ai.neargo.shop.food.dto.ServiceEntitlementContracts;
import ai.neargo.shop.food.dto.ServiceEntitlementContracts.ConfirmAttendance;
import ai.neargo.shop.food.dto.ServiceEntitlementContracts.Consume;
import ai.neargo.shop.food.dto.ServiceEntitlementContracts.Dish;
import ai.neargo.shop.food.dto.ServiceEntitlementContracts.Extension;
import ai.neargo.shop.food.dto.ServiceEntitlementContracts.Grant;
import ai.neargo.shop.food.dto.ServiceEntitlementContracts.Plan;
import ai.neargo.shop.food.dto.ServiceEntitlementContracts.QuoteRef;
import ai.neargo.shop.food.dto.ServiceSlotContracts.CreateReq;
import ai.neargo.shop.food.mapper.FoodBusinessOperationsMapper;
import ai.neargo.shop.food.mapper.ServiceEntitlementMapper;
import ai.neargo.shop.food.mapper.ServiceFormMapper;
import ai.neargo.shop.food.policy.MenuPublicationDocuments;
import ai.neargo.shop.food.policy.MenuSourcePolicy;
import ai.neargo.shop.food.policy.PublicationDocuments;
import ai.neargo.shop.food.service.CheckCartService;
import ai.neargo.shop.food.service.ConfigRevisionService;
import ai.neargo.shop.food.service.impl.ServiceEntitlementServiceImpl;
import ai.neargo.shop.food.service.impl.ServiceSlotServiceImpl;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.inventory.MaterialCatalogQueryPort;
import ai.neargo.shop.spi.product.CatalogPriceSourceFacts;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.GoodsFact;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.SkuFact;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.SkuIdentityFact;
import ai.neargo.shop.spi.trade.PricingCheckoutPort;
import ai.neargo.shop.spi.trade.PricingCheckoutPort.LineVO;
import ai.neargo.shop.spi.trade.PricingCheckoutPort.QuoteVO;
import ai.neargo.shop.spi.trade.PricingCheckoutPort.TotalsVO;
import ai.neargo.shop.spi.user.StoreCardQueryPort;
import ai.neargo.shop.spi.user.StoreDirectoryPort.StoreCard;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Actual V17/V24/V30 persistence and original capacity owner test per-person quotas, durable replay
 * and genuine quote references.
 */
public class ServiceEntitlementPersistenceTest {
    static final Instant NOW = Instant.parse("2026-10-29T08:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    final JsonMapper json = JsonMapper.builder().findAndAddModules().build();
    final ConfigRevisionService revisions = mock(ConfigRevisionService.class);
    final MaterialCatalogQueryPort materials = mock(MaterialCatalogQueryPort.class);
    final CatalogPriceSourcePort markets = mock(CatalogPriceSourcePort.class);
    final PricingCheckoutPort quotes = mock(PricingCheckoutPort.class);
    final CheckCartService carts = mock(CheckCartService.class);
    final StoreCardQueryPort stores = mock(StoreCardQueryPort.class);
    final QuoteRef quoteRef = new QuoteRef("CART", 3, "ORIGINAL_QUOTE", "a".repeat(64));
    JdbcTemplate db;
    SingleConnectionDataSource source;
    ServiceEntitlementServiceImpl service;
    ServiceSlotServiceImpl slots;
    TransactionTemplate tx;
    String menu;

    public static Timestamp utc(Integer precision) {
        return Timestamp.valueOf(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
    }

    @BeforeEach
    void databaseAndOriginalOwners() throws Exception {
        var ds =
                new SingleConnectionDataSource(
                        "jdbc:h2:mem:service_entitlements_"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "",
                        true);
        source = ds;
        db = new JdbcTemplate(ds);
        db.execute(
                "CREATE ALIAS IF NOT EXISTS UTC_TIMESTAMP FOR"
                        + " 'ai.neargo.shop.food.scenario.ServiceEntitlementPersistenceTest.utc'");
        db.execute("CREATE TABLE fnb_close_adjustment(id BIGINT PRIMARY KEY)");
        db.execute("CREATE TABLE fnb_business_share_plan(id BIGINT PRIMARY KEY)");
        for (var name :
                List.of(
                        "V17__food_service_collaboration_operations.sql",
                        "V24__food_service_forms.sql",
                        "V30__food_service_entitlements_and_banquet_plans.sql")) {
            String ddl = Files.readString(Path.of("src/main/resources/db/industry/food/" + name));
            ddl =
                    ddl.replaceAll("(?m)^--.*$", "")
                            .replace("CHARACTER SET utf8mb4 COLLATE utf8mb4_bin", "")
                            .replace(
                                    "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin", "")
                            .replace("ENGINE=InnoDB", "")
                            .replace(" JSON ", " VARCHAR(100000) ")
                            .replace(
                                    "ADD COLUMN batch_no VARCHAR(32)  NULL,",
                                    "ADD COLUMN batch_no VARCHAR(32) NULL; ALTER TABLE"
                                            + " fnb_capacity_claim");
            for (var statement : ddl.split(";")) if (!statement.isBlank()) db.execute(statement);
        }
        db.execute(
                "CREATE TABLE fnb_check(entity_no VARCHAR(32),store_no VARCHAR(32),check_no"
                        + " VARCHAR(32),party_size INT,status VARCHAR(16),service_state"
                        + " VARCHAR(16),version BIGINT,deleted INT DEFAULT 0)");
        db.update("INSERT INTO fnb_check VALUES('E','S','CHECK',1,'OPEN','OPEN',0,0)");
        db.execute(
                "CREATE TABLE fnb_check_cart(entity_no VARCHAR(32),store_no VARCHAR(32),cart_no"
                    + " VARCHAR(32),check_no VARCHAR(32),status VARCHAR(16),version BIGINT,deleted"
                    + " INT DEFAULT 0)");
        db.update("INSERT INTO fnb_check_cart VALUES('E','S','CART','CHECK','OPEN',3,0)");
        db.execute(
                "CREATE TABLE fnb_order_batch(entity_no VARCHAR(32),store_no VARCHAR(32),batch_no"
                        + " VARCHAR(32),check_no VARCHAR(32),status VARCHAR(16),deleted INT DEFAULT"
                        + " 0)");
        db.execute(
                "CREATE TABLE fnb_order_line(entity_no VARCHAR(32),batch_no"
                        + " VARCHAR(32),food_line_no VARCHAR(32),goods_no VARCHAR(32),sku_no"
                        + " VARCHAR(32),qty DECIMAL(18,6),unit VARCHAR(16),deleted INT DEFAULT 0)");
        // V17 already supplies the full original line-membership table and its scope/quantity
        // constraints.
        db.execute(
                "CREATE TABLE fnb_check_batch_membership(entity_no VARCHAR(32),batch_no"
                    + " VARCHAR(32),check_no VARCHAR(32),detached_at TIMESTAMP,deleted INT DEFAULT"
                    + " 0)");
        db.execute(
                "CREATE TABLE fnb_store_policy(entity_no VARCHAR(32),store_no VARCHAR(32),policy_no"
                        + " VARCHAR(32),deleted INT DEFAULT 0)");
        db.update("INSERT INTO fnb_store_policy VALUES('E','S','POLICY',0)");
        var manager = new DataSourceTransactionManager(ds);
        tx = new TransactionTemplate(manager);
        var operations = new FoodBusinessOperationsMapper(ds);
        slots = new ServiceSlotServiceImpl(operations, mock(IdempotencyService.class), CLOCK, json);
        service =
                new ServiceEntitlementServiceImpl(
                        new ServiceEntitlementMapper(ds),
                        new ServiceFormMapper(ds),
                        operations,
                        manager,
                        revisions,
                        materials,
                        markets,
                        quotes,
                        carts,
                        slots,
                        stores,
                        json,
                        CLOCK);
        var card = mock(StoreCard.class);
        when(card.entityNo()).thenReturn("E");
        when(card.active()).thenReturn(true);
        when(stores.cards(List.of("S"))).thenReturn(Map.of("S", card));
        var definition =
                new MenuConfigResultVO(
                        new MenuVO(
                                "MENU",
                                "S",
                                "Original menu",
                                "TIME",
                                "UTC",
                                0,
                                null,
                                "DRAFT",
                                0L,
                                0L),
                        new MenuScheduleVO(null, null, List.of(), List.of()),
                        new MenuCompositionVO(
                                List.of(
                                        new MenuCategoryInput(
                                                "CATEGORY",
                                                "Category",
                                                0,
                                                List.of(
                                                        new MenuItemInput(
                                                                "ITEM",
                                                                "GOODS",
                                                                0,
                                                                true,
                                                                "EXPLICIT",
                                                                List.of("SKU"),
                                                                "REPLACE",
                                                                List.of(),
                                                                List.of()))))),
                        List.of(new MenuContextInput("POS", "DINE_IN", null, null)));
        var body =
                new MenuPublicationSnapshot(
                        4,
                        "MENU",
                        "MENU",
                        0,
                        new MenuPublicationSnapshot.Captured(
                                "E",
                                "S",
                                definition,
                                List.of(
                                        new DishVO(
                                                "GOODS",
                                                "SINGLE",
                                                0,
                                                new DishPresentation(
                                                        null, List.of(), List.of(), List.of(),
                                                        List.of()),
                                                new MeasurementPolicy("EA", "COUNT", 0, null),
                                                0,
                                                0)),
                                List.of(new MenuPublicationSnapshot.ItemOptions("ITEM", List.of())),
                                List.of(),
                                List.of()),
                        List.of(new SkuIdentityFact("SKU", "GOODS", "E", "S", 0)),
                        List.of(
                                new GoodsFact(
                                        "GOODS",
                                        "E",
                                        "S",
                                        "Original goods",
                                        null,
                                        true,
                                        "APPROVED",
                                        0,
                                        List.of(new SkuFact("SKU", "Original spec", 0)))),
                        null);
        menu = MenuPublicationDocuments.freeze(json, body);
        var content =
                new ConfigRevisionContentVO(
                        new ConfigRevisionVO(
                                "MR",
                                "MENU",
                                "MENU",
                                1,
                                NOW.minusSeconds(600).atOffset(ZoneOffset.UTC),
                                PublicationDocuments.hash(json, menu),
                                "EFFECTIVE"),
                        menu);
        when(revisions.verifyMenuResource(any()))
                .thenAnswer(
                        a -> {
                            MenuSourceContracts.Query query = a.getArgument(0);
                            var unsigned =
                                    new MenuSourceContracts.Proof(
                                            1,
                                            query,
                                            content,
                                            MenuSourcePolicy.project(json, query, content),
                                            NOW.atOffset(ZoneOffset.UTC),
                                            NOW.plusSeconds(25).atOffset(ZoneOffset.UTC),
                                            null);
                            return new MenuSourceContracts.Proof(
                                    1,
                                    query,
                                    content,
                                    unsigned.resource(),
                                    unsigned.verifiedAt(),
                                    unsigned.validUntil(),
                                    MenuSourcePolicy.receipt(json, unsigned));
                        });
        var material =
                new MaterialCatalogQueryPort.MaterialFact(
                        "MATERIAL",
                        "E",
                        "Original goods",
                        "PIECE",
                        0,
                        "OWN",
                        "BASE_ONLY",
                        "INTEGER_ONLY",
                        MaterialCatalogFacts.definitionHash(
                                "MATERIAL", "E", "Original goods", "PIECE", 0, "OWN"));
        when(materials.getSkuMaterials("E", "S", List.of("SKU")))
                .thenReturn(List.of(new MaterialCatalogQueryPort.SkuMaterialFact("SKU", material)));
        when(markets.market(any()))
                .thenAnswer(
                        a ->
                                CatalogPriceSourceFacts.sealMarket(
                                        new CatalogPriceSourcePort.MarketFacts(
                                                1,
                                                "CATALOG_MARKET",
                                                a.getArgument(0),
                                                new CatalogPriceSourcePort.Market("CN", "CNY", 2),
                                                NOW,
                                                NOW.plusSeconds(25),
                                                null)));
        var cart =
                new CartVO(
                        "CART",
                        "S",
                        "CHECK",
                        null,
                        null,
                        "OPEN",
                        "POS",
                        "DINE_IN",
                        null,
                        3L,
                        List.of(
                                new OrderLineInput(
                                        "SOURCE_LINE",
                                        "MENU",
                                        "ITEM",
                                        "MR",
                                        "GOODS",
                                        "SKU",
                                        new BigDecimal("2"),
                                        null,
                                        null,
                                        List.of(),
                                        List.of())));
        when(carts.getCart("E", "S", "CART")).thenReturn(new CartResultVO(cart));
        when(quotes.revalidateStaff(any(), anyString(), any(), anyString(), anyString()))
                .thenAnswer(
                        a -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            return new QuoteVO(
                                    "ORIGINAL_QUOTE",
                                    "READY",
                                    NOW.plusSeconds(25).toString(),
                                    3,
                                    "b".repeat(64),
                                    "CNY",
                                    List.of(
                                            new LineVO(
                                                    "SOURCE_LINE",
                                                    "COMPONENT",
                                                    "INVOICE",
                                                    "MAIN",
                                                    "GOODS",
                                                    "SKU",
                                                    BigDecimal.ZERO,
                                                    new BigDecimal("2"),
                                                    190,
                                                    180,
                                                    9,
                                                    189)),
                                    new TotalsVO(190, 10, 180, 9, 189, 189),
                                    "a".repeat(64));
                        });
        form("BUFFET", "ACTIVE", 1);
    }

    @AfterEach
    void closeOriginalFixtureConnection() {
        if (source != null) source.destroy();
    }

    void form(String kind, String state, long version) {
        db.update("DELETE FROM fnb_service_attendee");
        db.update("DELETE FROM fnb_service_form");
        db.update(
                "INSERT INTO"
                    + " fnb_service_form(entity_no,store_no,form_no,kind,status,reservation_no,menu_no,menu_revision_no,menu_snapshot,"
                    + "guaranteed_persons,actual_persons,check_no,active_check_no,starts_at,ends_at,max_entries_per_person,version,created_at,updated_at,created_by,updated_by)"
                    + " VALUES('E','S','FORM',?,?,?,'MENU','MR',?,1,1,'CHECK',?,?,?,2,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),'A','A')",
                kind,
                state,
                "BANQUET".equals(kind) ? "RESERVATION" : null,
                menu,
                "ACTIVE".equals(state) ? "CHECK" : null,
                LocalDateTime.ofInstant(NOW.minusSeconds(60), ZoneOffset.UTC),
                LocalDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC),
                version);
        db.update(
                "INSERT INTO"
                    + " fnb_service_attendee(entity_no,store_no,attendee_no,form_no,guest_ref,person_class,status,entry_count,created_at,updated_at,created_by,updated_by)"
                    + " VALUES('E','S','ATTENDEE','FORM','ACTUAL_GUEST','ADULT','IN',1,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),'A','A')");
    }

    ServiceEntitlementContracts.Entitlement grant() {
        return service.grant(
                "E",
                "S",
                "A",
                "FORM",
                new Grant(
                        1,
                        "ATTENDEE",
                        "ITEM",
                        "GOODS",
                        "SKU",
                        new BigDecimal("5"),
                        quoteRef,
                        "Operational allowance"),
                "grant");
    }

    void originalLine(String batch, String line, String quantity, String check) {
        db.update("INSERT INTO fnb_order_batch VALUES('E','S',?,?,'ACCEPTED',0)", batch, check);
        db.update(
                "INSERT INTO fnb_order_line VALUES('E',?,?,'GOODS','SKU',?,'PIECE',0)",
                batch,
                line,
                new BigDecimal(quantity));
        db.update(
                "INSERT INTO"
                    + " fnb_service_form_batch(entity_no,store_no,form_no,batch_no,source_hash,created_at,updated_at,created_by,updated_by)"
                    + " VALUES('E','S','FORM',?, ?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),'A','A')",
                batch,
                "c".repeat(64));
    }

    @Test
    void exactOriginalFoodLineConsumesOnceAcrossKeysAndCannotExceedCanonicalPersonQuota() {
        var entitlement = grant();
        originalLine("BATCH", "LINE", "2", "CHECK");
        var request = new Consume(0, "BATCH", "LINE", "Served original accepted line");
        var first = service.consume("E", "S", "A", entitlement.entitlementNo(), request, "consume");
        assertThat(first.consumed()).isEqualByComparingTo("2");
        assertThat(first.unit()).isEqualTo("PIECE");
        assertThat(service.consume("E", "S", "A", entitlement.entitlementNo(), request, "consume"))
                .isEqualTo(first);
        assertThat(
                        service.consume(
                                "E",
                                "S",
                                "A",
                                entitlement.entitlementNo(),
                                request,
                                "different-key"))
                .isEqualTo(first);
        originalLine("BATCH2", "LINE2", "4", "CHECK");
        assertThatThrownBy(
                        () ->
                                service.consume(
                                        "E",
                                        "S",
                                        "A",
                                        entitlement.entitlementNo(),
                                        new Consume(1, "BATCH2", "LINE2", "Over quota"),
                                        "over"))
                .isInstanceOf(BizException.class);
        assertThat(service.entitlements("E", "S", "FORM").getFirst().consumed())
                .isEqualByComparingTo("2");
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM fnb_service_entitlement_event WHERE"
                                        + " event_kind='CONSUME'",
                                Integer.class))
                .isEqualTo(1);
    }

    @Test
    void exitedGuestAndForeignCurrentCheckCannotConsumeOrChangeTheirOriginalQuota() {
        var entitlement = grant();
        originalLine("BATCH", "LINE", "1", "OTHER_CHECK");
        assertThatThrownBy(
                        () ->
                                service.consume(
                                        "E",
                                        "S",
                                        "A",
                                        entitlement.entitlementNo(),
                                        new Consume(
                                                0, "BATCH", "LINE", "Wrong current responsibility"),
                                        "wrong"))
                .isInstanceOf(BizException.class);
        db.update("UPDATE fnb_order_batch SET check_no='CHECK'");
        db.update("UPDATE fnb_service_attendee SET status='OUT'");
        assertThatThrownBy(
                        () ->
                                service.consume(
                                        "E",
                                        "S",
                                        "A",
                                        entitlement.entitlementNo(),
                                        new Consume(0, "BATCH", "LINE", "Exited attendee"),
                                        "out"))
                .isInstanceOf(BizException.class);
        assertThat(service.entitlements("E", "S", "FORM").getFirst().consumed())
                .isEqualByComparingTo("0");
    }

    @Test
    void extensionUsesAdjacentActualCapacityAndPersistsOneOriginalQuoteReceipt() {
        var entitlement = grant();
        originalLine("BATCH", "LINE", "2", "CHECK");
        service.consume(
                "E",
                "S",
                "A",
                entitlement.entitlementNo(),
                new Consume(0, "BATCH", "LINE", "Original served line"),
                "consume-before-extension");
        var slot =
                tx.execute(
                        t ->
                                slots.create(
                                        "E",
                                        "S",
                                        "A",
                                        new CreateReq(
                                                NOW.plusSeconds(3600),
                                                NOW.plusSeconds(7200),
                                                NOW.plusSeconds(3000),
                                                NOW.plusSeconds(3600),
                                                new BigDecimal("10"),
                                                "PERSON"),
                                        "slot"));
        var request =
                new Extension(
                        2,
                        NOW.plusSeconds(7200),
                        slot.slotNo(),
                        quoteRef,
                        "Extend original service window");
        var result = service.extend("E", "S", "A", "FORM", request, "extend");
        assertThat(result.quoteNo()).isEqualTo("ORIGINAL_QUOTE");
        assertThat(service.extend("E", "S", "A", "FORM", request, "extend")).isEqualTo(result);
        assertThat(service.entitlements("E", "S", "FORM").getFirst().validUntil())
                .isEqualTo(NOW.plusSeconds(7200));
        assertThat(service.entitlements("E", "S", "FORM").getFirst().consumed())
                .isEqualByComparingTo("2");
        assertThat(service.entitlements("E", "S", "FORM").getFirst().granted())
                .isEqualByComparingTo("5");
        assertThat(db.queryForObject("SELECT consumed_qty FROM fnb_service_slot", BigDecimal.class))
                .isEqualByComparingTo("1");
        assertThatThrownBy(() -> service.extend("E", "S", "A", "FORM", request, "stale-version"))
                .isInstanceOf(BizException.class);
    }

    @Test
    void banquetIntendedBudgetAndActualAttendanceQuoteRemainSeparateAndDepositCannotBeInvented() {
        form("BANQUET", "BOOKED", 1);
        var plan =
                service.revisePlan(
                        "E",
                        "S",
                        "A",
                        "FORM",
                        new Plan(
                                1,
                                3,
                                1000,
                                List.of(
                                        new Dish(
                                                "ITEM", "GOODS", "SKU", new BigDecimal("2"), "EA")),
                                "Intended banquet plan"),
                        "plan");
        assertThat(plan.currency()).isEqualTo("CNY");
        assertThat(plan.intendedBudgetMinor()).isEqualTo(1000);
        db.update("UPDATE fnb_service_form SET status='ACTIVE',active_check_no='CHECK',version=3");
        var request =
                new ConfirmAttendance(3, plan.planNo(), quoteRef, null, "Confirm actual roster");
        var confirmed = service.confirmAttendance("E", "S", "A", "FORM", request, "confirm");
        assertThat(confirmed.actualPersons()).isEqualTo(1);
        assertThat(confirmed.calculatedReceivableMinor()).isEqualTo(189);
        assertThat(confirmed.quoteNo()).isEqualTo("ORIGINAL_QUOTE");
        assertThat(service.confirmAttendance("E", "S", "A", "FORM", request, "confirm"))
                .isEqualTo(confirmed);
        assertThatThrownBy(
                        () ->
                                service.confirmAttendance(
                                        "E",
                                        "S",
                                        "A",
                                        "FORM",
                                        new ConfirmAttendance(
                                                4,
                                                plan.planNo(),
                                                quoteRef,
                                                "CALLER_DEPOSIT",
                                                "Do not invent funds"),
                                        "deposit"))
                .isInstanceOf(BizException.class);
    }
}
