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
            this(db, hardware, null);
        }

        Fixture(
                MysqlDatabase db,
                boolean hardware,
                java.util.List<ai.neargo.commerce.api.catalog.SkuComponent> components) {
            this(db, hardware, components, 9000);
        }

        Fixture(
                MysqlDatabase db,
                boolean hardware,
                java.util.List<ai.neargo.commerce.api.catalog.SkuComponent> components,
                long price) {
            this.hardware = hardware;
            this.db = db;
            c = new JdbcCatalogFixture(db);
            c.setup();
            c.plans();
            c.product();
            var draft =
                    components != null
                            ? c.sku("sku-1", components)
                            : hardware
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
                                    : c.sku();
            c.facade.saveSku(
                    c.ops,
                    new ai.neargo.commerce.api.catalog.SkuDraft(
                            draft.productNo(),
                            draft.skuNo(),
                            draft.skuVersionNo(),
                            draft.title(),
                            new Money("CNY", Math.max(10000, price)),
                            new Money("CNY", price),
                            draft.allowedBusinessTypes(),
                            draft.refundPolicySnapshot(),
                            draft.components(),
                            draft.maxPerOrder(),
                            draft.expectedVersion(),
                            draft.reason()));
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
                            new JdbcMallRepository(db.ds, new JdbcCommerceEventPersistence(db.ds)),
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
    void assistedReplayFindsPersistedStoreBuyerAndKeepsOperatorIsolation() {
        try (var db = new MysqlDatabase()) {
            db.migrate();
            var f = new Fixture(db);
            var terms = new ai.neargo.commerce.api.offline.OfflineCollectionTerms(
                    "test", 1, "platform", "CNY", "test-only", "w1", 86400000, Map.of("bank", "test-only"));
            var command = new QuoteCommand(STORE, List.of(new QuoteLine("sku-1", 1)), "CNY");
            var other = new ActorContext("tenant-a", SubjectKind.OPERATOR, "other-operator", "trusted");
            var q = f.api.quoteOffline(f.c.ops, command, terms);
            var otherQuote = f.api.quoteOffline(other, command, terms);
            var place = new PlaceOrderCommand(q.quoteNo(), "assisted-replay", Optional.empty(), STORE);
            var created = f.api.placeOrder(f.c.ops, place);
            assertEquals(created, f.api.placeOrder(f.c.ops, place));
            assertEquals(1, db.jdbc.queryForObject("SELECT COUNT(*) FROM mall_order", Integer.class));
            assertEquals(created.orderNo(), db.jdbc.queryForObject(
                    "SELECT pending_change_order_no FROM mch_subscription_account", String.class));
            f.c.uow.required(() -> {
                new JdbcSubscriptionAccountRepository(db.ds).lockSubscriptionAccount(STORE);
                var repo = new JdbcMallRepository(db.ds, new JdbcCommerceEventPersistence(db.ds));
                var frozen = repo.quote("tenant-a", otherQuote.quoteNo()).orElseThrow();
                assertTrue(repo.byIdempotency(frozen, "assisted-replay").isEmpty());
                return null;
            });
            assertEquals("NOT_FOUND", assertThrows(MallException.class,
                    () -> f.api.placeOrder(other, place)).code());
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
                                return new JdbcMallRepository(
                                                db.ds, new JdbcCommerceEventPersistence(db.ds))
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
                                        var first = f.place(q, "rollback");
                                        assertEquals(first, f.place(q, "rollback"));
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
            assertEquals(
                    "VALID",
                    db.jdbc.queryForObject(
                            "SELECT status FROM mall_quote WHERE quote_no=?",
                            String.class,
                            q.quoteNo()));
            assertEquals(
                    0,
                    db.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM commerce_event_inbox WHERE"
                                    + " source_system='MALL_ORDER_IDEMPOTENCY'",
                            Integer.class));
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
                                new JdbcMallRepository(
                                                db.ds, new JdbcCommerceEventPersistence(db.ds))
                                        .components(STORE, order.orderNo());
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
                                new JdbcMallRepository(
                                                db.ds, new JdbcCommerceEventPersistence(db.ds))
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

    private Fixture crossStoreZeroLotFixture(MysqlDatabase db) {
        var f = new Fixture(db, true);
        var second = new StoreRef("tenant-a", "store-second");
        var subscription = new JdbcSubscriptionRepository(db.ds, f.c.ids);
        f.c.uow.required(
                () -> {
                    subscription.ensureAccount(
                            new StoreSnapshot(
                                    second,
                                    "merchant",
                                    "RETAIL",
                                    StoreStatus.ACTIVE,
                                    CommerceModel.STORE_V1),
                            "buyer");
                    subscription.insert(
                            subscription.lock(second),
                            new ai.neargo.commerce.core.spi.Period(
                                    "free-second",
                                    "initialize-second",
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
                    + " ('tenant-a','store-second','CNY',0,0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),0,0)");
        db.jdbc.update("DELETE FROM mch_subscription_credit_lot");
        db.jdbc.update("UPDATE mch_subscription_credit_account SET balance_minor=0");
        return f;
    }

    private List<String> crossStoreRace(Fixture f, boolean sameKey, boolean joined)
            throws Exception {
        assertEquals(
                "REPEATABLE-READ",
                f.db.jdbc.queryForObject("SELECT @@transaction_isolation", String.class));
        var second = new StoreRef("tenant-a", "store-second");
        var quotes =
                List.of(
                        f.quote(),
                        f.api.quote(
                                BUYER,
                                new QuoteCommand(
                                        second, List.of(new QuoteLine("sku-1", 1)), "CNY")));
        var both = new CountDownLatch(2);
        var delegate = new JdbcMallRepository(f.db.ds, new JdbcCommerceEventPersistence(f.db.ds));
        var proxy =
                (MallRepository)
                        java.lang.reflect.Proxy.newProxyInstance(
                                MallRepository.class.getClassLoader(),
                                new Class<?>[] {MallRepository.class},
                                (p, m, a) -> {
                                    boolean before =
                                            sameKey
                                                    ? m.getName().equals("claimOrder")
                                                    : m.getName().equals("create");
                                    if (before) {
                                        both.countDown();
                                        if (!both.await(10, TimeUnit.SECONDS))
                                            throw new AssertionError(
                                                    "different stores did not reach the"
                                                            + " claim/create concurrently");
                                    }
                                    Object result;
                                    try {
                                        result = m.invoke(delegate, a);
                                    } catch (java.lang.reflect.InvocationTargetException e) {
                                        throw e.getCause();
                                    }
                                    return result;
                                });
        var api =
                new DefaultMallFacade(
                        proxy,
                        f.c.repo,
                        new JdbcSubscriptionAccountRepository(f.db.ds),
                        f.c.uow,
                        store ->
                                Optional.of(
                                        new StoreSnapshot(
                                                store,
                                                "merchant",
                                                "RETAIL",
                                                StoreStatus.ACTIVE,
                                                CommerceModel.STORE_V1)),
                        (a, store, cap) ->
                                new AuthorizationDecision(true, "OWNER", Optional.empty()),
                        Clock.fixed(Instant.ofEpochMilli(2000), ZoneOffset.UTC),
                        f.c.ids);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var jobs = new ArrayList<Future<String>>();
            int sequence = 0;
            for (var q : quotes) {
                String key = sameKey ? "cross-store-key" : "different-key-" + (++sequence);
                jobs.add(
                        pool.submit(
                                () -> {
                                    java.util.function.Supplier<String> work =
                                            () -> {
                                                api.placeOrder(
                                                        BUYER,
                                                        new PlaceOrderCommand(
                                                                q.quoteNo(),
                                                                key,
                                                                Optional.of(
                                                                        new ShippingAddress(
                                                                                "r", "p", "CN",
                                                                                "region", "city",
                                                                                "address",
                                                                                "zip"))));
                                                return "ORDER";
                                            };
                                    try {
                                        return joined ? f.c.uow.required(work) : work.get();
                                    } catch (MallException e) {
                                        return e.code();
                                    }
                                }));
            }
            var result = new ArrayList<String>();
            for (var job : jobs) result.add(job.get(20, TimeUnit.SECONDS));
            Collections.sort(result);
            return result;
        }
    }

    private void assertNoPartialCrossStoreReservations(MysqlDatabase db, int orders) {
        for (String table :
                List.of("mall_order", "mall_order_item", "mall_order_component", "mall_shipment"))
            assertEquals(
                    orders, db.jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class));
        for (String table :
                List.of("mch_subscription_reservation", "mch_subscription_credit_ledger"))
            assertEquals(0, db.jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class));
        assertEquals(
                0,
                db.jdbc.queryForObject(
                        "SELECT COUNT(*) FROM mch_subscription_account WHERE"
                                + " pending_change_order_no IS NOT NULL",
                        Integer.class));
        assertEquals(
                0,
                db.jdbc.queryForObject(
                        "SELECT SUM(reserved_minor) FROM mch_subscription_credit_account",
                        Long.class));
    }

    @Test
    void crossStoreSameBuyerKeyRaceReturnsOneOrderAndDomainConflictWithZeroLots() throws Exception {
        try (var db = new MysqlDatabase()) {
            db.migrate();
            var f = crossStoreZeroLotFixture(db);
            assertEquals(List.of("IDEMPOTENCY_CONFLICT", "ORDER"), crossStoreRace(f, true, false));
            assertNoPartialCrossStoreReservations(db, 1);
        }
    }

    @Test
    void crossStoreDistinctKeysProgressConcurrentlyWithoutAbsentOrderGapLocks() throws Exception {
        try (var db = new MysqlDatabase()) {
            db.migrate();
            var f = crossStoreZeroLotFixture(db);
            assertEquals(List.of("ORDER", "ORDER"), crossStoreRace(f, false, false));
            assertNoPartialCrossStoreReservations(db, 2);
        }
    }

    @Test
    void joinedOuterCrossStoreRaceReturnsConflictAfterRollbackWithoutRetryingPoisonedTransaction()
            throws Exception {
        try (var db = new MysqlDatabase()) {
            db.migrate();
            var f = crossStoreZeroLotFixture(db);
            assertEquals(List.of("IDEMPOTENCY_CONFLICT", "ORDER"), crossStoreRace(f, true, true));
            assertNoPartialCrossStoreReservations(db, 1);
        }
    }

    @Test
    void caughtJoinedConflictCannotReturnSuccessFromRollbackOnlyOuterTransaction() {
        try (var db = new MysqlDatabase()) {
            db.migrate();
            var f = crossStoreZeroLotFixture(db);
            var q = f.quote();
            f.place(q, "same-outer-key");
            var second =
                    f.api.quote(
                            BUYER,
                            new QuoteCommand(
                                    new StoreRef("tenant-a", "store-second"),
                                    List.of(new QuoteLine("sku-1", 1)),
                                    "CNY"));
            assertThrows(
                    org.springframework.transaction.UnexpectedRollbackException.class,
                    () ->
                            f.c.uow.required(
                                    () -> {
                                        assertEquals(
                                                "IDEMPOTENCY_CONFLICT",
                                                assertThrows(
                                                                MallException.class,
                                                                () ->
                                                                        f.place(
                                                                                second,
                                                                                "same-outer-key"))
                                                        .code());
                                        return "caller attempted to suppress the rollback";
                                    }));
            assertNoPartialCrossStoreReservations(db, 1);
            assertEquals(
                    "VALID",
                    db.jdbc.queryForObject(
                            "SELECT status FROM mall_quote WHERE quote_no=?",
                            String.class,
                            second.quoteNo()));
        }
    }

    @Test
    void configuredEventPortDelegatesThreeConcurrentImmutableDuplicateReplays() throws Exception {
        try (var db = new MysqlDatabase()) {
            db.migrate();
            var f = new Fixture(db, true);
            var q = f.quote();
            var reached = new CountDownLatch(3);
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            var actual = new JdbcCommerceEventPersistence(db.ds);
            var custom =
                    (ai.neargo.commerce.api.port.CommerceEventPersistencePort)
                            java.lang.reflect.Proxy.newProxyInstance(
                                    ai.neargo.commerce.api.port.CommerceEventPersistencePort.class
                                            .getClassLoader(),
                                    new Class<?>[] {
                                        ai.neargo.commerce.api.port.CommerceEventPersistencePort
                                                .class
                                    },
                                    (p, m, a) -> {
                                        if (m.getName().equals("recordInbound")
                                                && calls.incrementAndGet() > 1) {
                                            reached.countDown();
                                            if (!reached.await(10, TimeUnit.SECONDS))
                                                throw new AssertionError(
                                                        "replays did not enter the configured"
                                                                + " adapter concurrently");
                                        }
                                        try {
                                            return m.invoke(actual, a);
                                        } catch (java.lang.reflect.InvocationTargetException e) {
                                            throw e.getCause();
                                        }
                                    });
            var api =
                    new DefaultMallFacade(
                            new JdbcMallRepository(db.ds, custom),
                            f.c.repo,
                            new JdbcSubscriptionAccountRepository(db.ds),
                            f.c.uow,
                            store ->
                                    Optional.of(
                                            new StoreSnapshot(
                                                    store,
                                                    "merchant",
                                                    "RETAIL",
                                                    StoreStatus.ACTIVE,
                                                    CommerceModel.STORE_V1)),
                            (a, store, cap) ->
                                    new AuthorizationDecision(true, "OWNER", Optional.empty()),
                            Clock.fixed(Instant.ofEpochMilli(2000), ZoneOffset.UTC),
                            f.c.ids);
            var command =
                    new PlaceOrderCommand(
                            q.quoteNo(),
                            "custom-port",
                            Optional.of(
                                    new ShippingAddress(
                                            "r", "p", "CN", "region", "city", "address", "zip")));
            var first = api.placeOrder(BUYER, command);
            var before =
                    db.jdbc.queryForMap(
                            "SELECT * FROM commerce_event_inbox WHERE"
                                    + " source_system='MALL_ORDER_IDEMPOTENCY'");
            try (var pool = Executors.newFixedThreadPool(3)) {
                var jobs = new ArrayList<Future<OrderView>>();
                for (int i = 0; i < 3; i++)
                    jobs.add(pool.submit(() -> api.placeOrder(BUYER, command)));
                for (var job : jobs) assertEquals(first, job.get(20, TimeUnit.SECONDS));
            }
            var late =
                    new DefaultMallFacade(
                            new JdbcMallRepository(db.ds, custom),
                            f.c.repo,
                            new JdbcSubscriptionAccountRepository(db.ds),
                            f.c.uow,
                            store ->
                                    Optional.of(
                                            new StoreSnapshot(
                                                    store,
                                                    "merchant",
                                                    "RETAIL",
                                                    StoreStatus.ACTIVE,
                                                    CommerceModel.STORE_V1)),
                            (a, store, cap) ->
                                    new AuthorizationDecision(true, "OWNER", Optional.empty()),
                            Clock.fixed(Instant.ofEpochMilli(1002000), ZoneOffset.UTC),
                            f.c.ids);
            assertEquals(first, late.placeOrder(BUYER, command));
            assertEquals(5, calls.get());
            assertEquals(
                    before,
                    db.jdbc.queryForMap(
                            "SELECT * FROM commerce_event_inbox WHERE"
                                    + " source_system='MALL_ORDER_IDEMPOTENCY'"));
            assertNoPartialCrossStoreReservations(db, 1);
        }
    }
}
