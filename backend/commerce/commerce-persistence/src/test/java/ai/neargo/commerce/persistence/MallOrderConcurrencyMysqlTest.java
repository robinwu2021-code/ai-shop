package ai.neargo.commerce.persistence;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.mall.*;
import ai.neargo.commerce.core.mall.*;
import ai.neargo.commerce.core.spi.*;

import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

class MallOrderConcurrencyMysqlTest {
    static final StoreRef STORE = new StoreRef("tenant-a", "store");
    static final ActorContext BUYER =
            new ActorContext("tenant-a", SubjectKind.ACCOUNT, "buyer", "trusted");

    static class Fixture {
        final MysqlDatabase db;
        final JdbcCatalogFixture c;
        final DefaultMallFacade api;
        final boolean hardware;

        Fixture(MysqlDatabase db) {
            this(db, false);
        }

        Fixture(MysqlDatabase db, boolean hardware) {
            this.hardware = hardware;
            this.db = db;
            c = new JdbcCatalogFixture(db);
            c.setup();
            c.plans();
            c.product();
            c.facade.saveSku(
                    c.ops,
                    hardware
                            ? c.sku(
                                    "sku-1",
                                    List.of(
                                            c.component(
                                                    "hw",
                                                    ComponentKind.HARDWARE,
                                                    null,
                                                    null,
                                                    "device",
                                                    null,
                                                    2,
                                                    100,
                                                    false)))
                            : c.sku());
            c.facade.publishIndustry(c.ops, c.publication(List.of("sku-1"), 0));
            var type =
                    c.repo.load("tenant-a", ai.neargo.commerce.core.catalog.CatalogLockMode.NONE)
                            .types
                            .get("RETAIL");
            c.facade.saveBusinessType(
                    c.ops,
                    new ai.neargo.commerce.api.catalog.BusinessTypeDraft(
                            "RETAIL",
                            "零售",
                            1,
                            true,
                            type.version(),
                            "enable published test catalog"));
            var subscription = new JdbcSubscriptionRepository(db.ds, c.ids);
            c.uow.required(
                    () -> {
                        subscription.ensureAccount(
                                new StoreSnapshot(
                                        STORE,
                                        "merchant",
                                        "RETAIL",
                                        StoreStatus.ACTIVE,
                                        CommerceModel.STORE_V1),
                                "buyer");
                        subscription.insert(
                                subscription.lock(STORE),
                                new ai.neargo.commerce.core.spi.Period(
                                        "free",
                                        "initialize",
                                        "FREE",
                                        "RETAIL:FREE",
                                        "free-1",
                                        "FREE",
                                        1000,
                                        1000,
                                        null,
                                        Map.of("BASE", "BASE"),
                                        0,
                                        0,
                                        "CNY",
                                        "policy",
                                        false),
                                1,
                                "buyer");
                        return null;
                    });
            db.jdbc.update(
                    "INSERT INTO"
                        + " mch_subscription_credit_account(tenant_no,store_no,currency,balance_minor,reserved_minor,created_at,updated_at,version,deleted)"
                        + " VALUES"
                        + " ('tenant-a','store','CNY',200,0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),0,0)");
            for (int i = 1; i <= 2; i++)
                db.jdbc.update(
                        "INSERT INTO"
                            + " mch_subscription_credit_lot(tenant_no,credit_lot_no,store_no,currency,source_type,source_ref_no,issued_minor,remaining_minor,reserved_minor,parent_provenance,created_at,updated_at,version,deleted)"
                            + " VALUES"
                            + " ('tenant-a',?,'store','CNY','TEST',?,100,100,0,'{}',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),0,0)",
                        "lot-" + i,
                        "source-" + i);
            api =
                    new DefaultMallFacade(
                            new JdbcMallRepository(db.ds),
                            c.repo,
                            new JdbcSubscriptionAccountRepository(db.ds),
                            c.uow,
                            s ->
                                    Optional.of(
                                            new StoreSnapshot(
                                                    s,
                                                    "merchant",
                                                    "RETAIL",
                                                    StoreStatus.ACTIVE,
                                                    CommerceModel.STORE_V1)),
                            (a, s, cap) ->
                                    new AuthorizationDecision(
                                            "trusted".equals(a.accountSessionFingerprint()),
                                            "OWNER",
                                            Optional.empty()),
                            Clock.fixed(Instant.ofEpochMilli(2000), ZoneOffset.UTC),
                            c.ids);
        }

        QuoteView quote() {
            return api.quote(
                    BUYER, new QuoteCommand(STORE, List.of(new QuoteLine("sku-1", 1)), "CNY"));
        }

        OrderView place(QuoteView q, String key) {
            return api.placeOrder(
                    BUYER,
                    new PlaceOrderCommand(
                            q.quoteNo(),
                            key,
                            hardware
                                    ? Optional.of(
                                            new ShippingAddress(
                                                    "recipient",
                                                    "phone",
                                                    "CN",
                                                    "region",
                                                    "city",
                                                    "address",
                                                    "postal"))
                                    : Optional.empty()));
        }
    }

    @Test
    void twoIndependentTransactionsHaveOnlyOneDigitalOccupancyAndExactFifoReservations()
            throws Exception {
        try (var db = new MysqlDatabase()) {
            db.migrate();
            var f = new Fixture(db);
            var a = f.quote();
            var b = f.quote();
            assertEquals(
                    1,
                    db.jdbc.queryForObject(
                            "SELECT COUNT(DISTINCT request_hash) FROM mall_quote", Integer.class));
            var start = new CountDownLatch(1);
            var connectionIds = ConcurrentHashMap.<Long>newKeySet();
            try (var pool = Executors.newFixedThreadPool(2)) {
                var jobs = new ArrayList<Future<String>>();
                for (var q : List.of(a, b))
                    jobs.add(
                            pool.submit(
                                    () -> {
                                        start.await();
                                        try {
                                            return f.c.uow.required(
                                                    () -> {
                                                        connectionIds.add(
                                                                db.jdbc.queryForObject(
                                                                        "SELECT CONNECTION_ID()",
                                                                        Long.class));
                                                        return f.place(q, q.quoteNo()).orderNo();
                                                    });
                                        } catch (MallException e) {
                                            assertTrue(
                                                    Set.of("ACCOUNT_BUSY", "VERSION_CONFLICT")
                                                            .contains(e.code()));
                                            return null;
                                        }
                                    }));
                start.countDown();
                int winners = 0;
                for (var job : jobs) if (job.get(15, TimeUnit.SECONDS) != null) winners++;
                assertEquals(1, winners);
                assertEquals(2, connectionIds.size());
            }
            assertEquals(
                    1, db.jdbc.queryForObject("SELECT COUNT(*) FROM mall_order", Integer.class));
            assertNotNull(
                    db.jdbc.queryForObject(
                            "SELECT pending_change_order_no FROM mch_subscription_account",
                            String.class));
            assertEquals(
                    200,
                    db.jdbc.queryForObject(
                            "SELECT reserved_minor FROM mch_subscription_credit_account",
                            Long.class));
            assertEquals(
                    List.of(100L, 100L),
                    db.jdbc.queryForList(
                            "SELECT reserved_minor FROM mch_subscription_credit_lot ORDER BY id",
                            Long.class));
            assertEquals(
                    2,
                    db.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM mch_subscription_credit_ledger WHERE"
                                    + " event_type='RESERVE'",
                            Integer.class));
            assertEquals(
                    1,
                    db.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM mch_subscription_reservation", Integer.class));
        }
    }

    @Test
    void sameKeyRaceReturnsOneOrderAndSnapshotsSurviveRepositoryRecreationAndCancelKeepsHolds()
            throws Exception {
        try (var db = new MysqlDatabase()) {
            db.migrate();
            var f = new Fixture(db);
            var q = f.quote();
            var start = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(2)) {
                var a =
                        pool.submit(
                                () -> {
                                    start.await();
                                    return f.place(q, "same");
                                });
                var b =
                        pool.submit(
                                () -> {
                                    start.await();
                                    return f.place(q, "same");
                                });
                start.countDown();
                assertEquals(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
            }
            var o = f.place(q, "same");
            assertEquals(
                    "UNPAID",
                    db.jdbc.queryForObject("SELECT pay_status FROM mall_order", String.class));
            assertEquals(
                    "PENDING",
                    db.jdbc.queryForObject(
                            "SELECT fulfillment_status FROM mall_order", String.class));
            assertEquals(o, f.api.order(BUYER, new OrderQuery(STORE, o.orderNo())));
            var snapshot =
                    f.c.uow.required(
                            () -> {
                                new JdbcSubscriptionAccountRepository(db.ds)
                                        .lockSubscriptionAccount(STORE);
                                return new JdbcMallRepository(db.ds)
                                        .order("tenant-a", o.orderNo())
                                        .orElseThrow();
                            });
            assertEquals(
                    "pro-1",
                    snapshot.quote()
                            .lines()
                            .getFirst()
                            .components()
                            .getFirst()
                            .plan()
                            .planVersionNo());
            assertEquals(
                    "POS",
                    snapshot.quote().lines().getFirst().components().getFirst().ends().get("POS"));
            f.api.cancelOrder(BUYER, new CancelOrderCommand(STORE, o.orderNo(), "cancel", "test"));
            assertEquals(
                    "CANCEL_PENDING",
                    db.jdbc.queryForObject("SELECT pay_status FROM mall_order", String.class));
            assertEquals(
                    "PENDING",
                    db.jdbc.queryForObject(
                            "SELECT fulfillment_status FROM mall_order", String.class));
            assertEquals(
                    OrderStatus.CANCEL_PENDING,
                    f.api.order(BUYER, new OrderQuery(STORE, o.orderNo())).status());
            assertEquals(
                    200,
                    db.jdbc.queryForObject(
                            "SELECT reserved_minor FROM mch_subscription_credit_account",
                            Long.class));
            assertEquals(
                    o.orderNo(),
                    db.jdbc.queryForObject(
                            "SELECT pending_change_order_no FROM mch_subscription_account",
                            String.class));
        }
    }

    @Test
    void localRollbackRemovesOrderItemsComponentsCreditLedgerAndOccupancy() {
        try (var db = new MysqlDatabase()) {
            db.migrate();
            var f = new Fixture(db);
            var q = f.quote();
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            f.c.uow.required(
                                    () -> {
                                        f.place(q, "rollback");
                                        throw new IllegalStateException("rollback");
                                    }));
            for (String table :
                    List.of(
                            "mall_order",
                            "mall_order_item",
                            "mall_order_component",
                            "mch_subscription_reservation",
                            "mch_subscription_credit_ledger"))
                assertEquals(
                        0, db.jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class));
            assertNull(
                    db.jdbc.queryForObject(
                            "SELECT pending_change_order_no FROM mch_subscription_account",
                            String.class));
            assertEquals(
                    0,
                    db.jdbc.queryForObject(
                            "SELECT reserved_minor FROM mch_subscription_credit_account",
                            Long.class));
            assertNotNull(f.place(q, "rollback"));
        }
    }

    @Test
    void hardwareOrdersNeverReserveDigitalAccountAndAddressAndInventoryStatePersist() {
        try (var db = new MysqlDatabase()) {
            db.migrate();
            var f = new Fixture(db, true);
            var q = f.quote();
            assertEquals(0, q.credit().minor());
            var first = f.place(q, "hardware-one");
            var second = f.place(f.quote(), "hardware-two");
            assertEquals(OrderStatus.PENDING_INVENTORY, first.status());
            assertEquals(OrderStatus.PENDING_INVENTORY, second.status());
            assertEquals(first, f.place(q, "hardware-one"));
            assertNull(
                    db.jdbc.queryForObject(
                            "SELECT pending_change_order_no FROM mch_subscription_account",
                            String.class));
            assertEquals(
                    0,
                    db.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM mch_subscription_reservation", Integer.class));
            assertEquals(
                    0,
                    db.jdbc.queryForObject(
                            "SELECT reserved_minor FROM mch_subscription_credit_account",
                            Long.class));
            assertEquals(
                    2,
                    db.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM mall_shipment WHERE status='PENDING_INVENTORY'"
                                    + " AND address_snapshot->>'$.recipient'='recipient'",
                            Integer.class));
            assertEquals(
                    0,
                    db.jdbc.queryForObject("SELECT COUNT(*) FROM mall_payment_ref", Integer.class));
        }
    }

    @Test
    void pendingHardwareShipmentReferencesPersistedOrderComponentIdentity() {
        try (var db = new MysqlDatabase()) {
            db.migrate();
            var f = new Fixture(db, true);
            var order = f.place(f.quote(), "identity");
            f.c.uow.required(
                    () -> {
                        new JdbcSubscriptionAccountRepository(db.ds).lockSubscriptionAccount(STORE);
                        var components =
                                new JdbcMallRepository(db.ds).components(STORE, order.orderNo());
                        assertEquals(1, components.size());
                        assertEquals(
                                "device",
                                components
                                        .getFirst()
                                        .snapshot()
                                        .allocation()
                                        .component()
                                        .hardwareSkuNo());
                        assertEquals(
                                2, components.getFirst().snapshot().allocation().cycles().size());
                        assertEquals(10000, components.getFirst().snapshot().allocation().gross());
                        assertEquals(9000, components.getFirst().snapshot().allocation().deal());
                        assertTrue(
                                new JdbcMallRepository(db.ds)
                                        .components(
                                                new StoreRef("tenant-a", "other-store"),
                                                order.orderNo())
                                        .isEmpty());
                        return null;
                    });
            assertEquals(
                    1,
                    db.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM mall_shipment s JOIN"
                                + " JSON_TABLE(s.shipment_items,'$[*]' COLUMNS(c VARCHAR(64) PATH"
                                + " '$.orderComponentNo')) j ON TRUE JOIN mall_order_component c ON"
                                + " c.tenant_no=s.tenant_no AND c.order_component_no=j.c",
                            Integer.class));
        }
    }

    @Test
    void stopSaleRejectsUnplacedQuoteButGrandfathersExistingOrderSnapshot() {
        try (var db = new MysqlDatabase()) {
            db.migrate();
            var f = new Fixture(db, true);
            var placedQuote = f.quote();
            var unplaced = f.quote();
            var created = f.place(placedQuote, "created-before-stop");
            var sku =
                    f.c.repo
                            .load("tenant-a", ai.neargo.commerce.core.catalog.CatalogLockMode.NONE)
                            .skus
                            .get("sku");
            f.c.facade.stopSale(
                    f.c.ops,
                    new ai.neargo.commerce.api.catalog.StopSaleCommand(
                            "stop",
                            ai.neargo.commerce.api.catalog.SaleTarget.SKU,
                            "sku",
                            sku.version(),
                            "stop new sales"));
            assertEquals(
                    "NOT_SELLABLE",
                    assertThrows(MallException.class, () -> f.place(unplaced, "after-stop"))
                            .code());
            assertEquals(created, f.place(placedQuote, "created-before-stop"));
            assertEquals(created, f.api.order(BUYER, new OrderQuery(STORE, created.orderNo())));
            assertEquals(
                    1, db.jdbc.queryForObject("SELECT COUNT(*) FROM mall_order", Integer.class));
        }
    }
}
