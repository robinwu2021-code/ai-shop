package ai.neargo.commerce.persistence;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.fulfillment.*;
import ai.neargo.commerce.api.mall.*;
import ai.neargo.commerce.api.port.*;
import ai.neargo.commerce.core.access.*;
import ai.neargo.commerce.core.fulfillment.*;
import ai.neargo.commerce.core.spi.CommerceUnitOfWork;
import ai.neargo.commerce.core.subscription.*;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.*;
import java.util.*;

class MallFulfillmentMysqlTest {
    @Test
    void providerBucketsRespectDeadlineAndLegacyFactReplay() {
        for (long precision : List.of(1L, 1000L)) {
            for (long offset : List.of(0L, -1000L)) {
                try (var db = new MysqlDatabase()) {
                    var f = setup(db, false);
                    long deadline =
                            db.jdbc.queryForObject("SELECT expires_at FROM mall_quote", Long.class)
                                    + 250;
                    // Fixture a valid subsecond cutoff before preparing the payment attempt.
                    db.jdbc.update(
                            "UPDATE mall_quote SET"
                                + " expires_at=?,line_snapshot=JSON_SET(line_snapshot,'$.expiresAt',?)",
                            deadline,
                            deadline);
                    db.jdbc.update("UPDATE mall_order SET pay_deadline=?", deadline);
                    start(f);
                    Instant actual =
                            Instant.ofEpochMilli(
                                    (precision == 1 ? deadline : deadline / 1000 * 1000) + offset);
                    f.bridge.payment =
                            new PaymentOutcome(
                                    f.order.orderNo(),
                                    Optional.of("pay"),
                                    "platform",
                                    f.order.payable(),
                                    AsyncStatus.SUCCEEDED,
                                    Optional.of(actual),
                                    Optional.empty(),
                                    Map.of(),
                                    false,
                                    Optional.empty(),
                                    precision);
                    f.svc.refresh(new FulfillmentQuery("tenant-a", f.order.orderNo()));
                    assertEquals(
                            "PAID",
                            db.jdbc.queryForObject(
                                    "SELECT pay_status FROM mall_order", String.class));
                    assertEquals(
                            precision == 1000 && offset == 0 ? "FAILED" : "COMPLETED",
                            db.jdbc.queryForObject(
                                    "SELECT fulfillment_status FROM mall_order", String.class));
                    assertEquals(
                            actual.toEpochMilli(),
                            db.jdbc.queryForObject("SELECT paid_at FROM mall_order", Long.class));
                    if (precision == 1000 && offset == 0) {
                        assertEquals(
                                "REFUNDING",
                                db.jdbc.queryForObject(
                                        "SELECT refund_status FROM mall_order", String.class));
                        assertEquals(
                                0L,
                                count(
                                        db,
                                        "SELECT count(*) FROM mall_fulfillment_task WHERE"
                                            + " task_type='DIGITAL'"));
                    }
                    f.svc.refresh(new FulfillmentQuery("tenant-a", f.order.orderNo()));
                    assertEquals(
                            1L,
                            count(
                                    db,
                                    "SELECT count(*) FROM commerce_event_inbox WHERE"
                                        + " source_system='MALL_PAYMENT'"));
                }
            }
        }
        try (var db = new MysqlDatabase()) {
            var f = setup(db, false);
            start(f);
            var original = paid(f, 8800);
            f.svc.confirmPayment(original);
            assertEquals(
                    ai.neargo.commerce.core.spi.FactDigest.of(
                            List.of(
                                    original.orderNo(),
                                    original.paymentNo(),
                                    original.platformMerchantNo(),
                                    original.paid().currency(),
                                    Long.toString(original.paid().minor()),
                                    original.paidAt().toString())),
                    db.jdbc.queryForObject(
                            "SELECT payload_hash FROM commerce_event_inbox WHERE"
                                + " source_system='MALL_PAYMENT'",
                            String.class));
            var json = tools.jackson.databind.json.JsonMapper.builder().build();
            String oldJson =
                    json.writeValueAsString(original).replace(",\"paidAtPrecisionMs\":1", "");
            assertFalse(oldJson.contains("paidAtPrecisionMs"));
            db.jdbc.update(
                    "UPDATE mall_payment_ref SET"
                        + " outcome_snapshot=JSON_REMOVE(outcome_snapshot,'$.paidAtPrecisionMs'),verified_fact_snapshot=JSON_REMOVE(verified_fact_snapshot,'$.paidAtPrecisionMs')");
            var legacy = json.readValue(oldJson, VerifiedPaymentFact.class);
            assertEquals(1L, legacy.paidAtPrecisionMs());
            f.svc.confirmPayment(legacy);
            assertThrows(
                    InboundFactConflictException.class,
                    () ->
                            f.svc.confirmPayment(
                                    new VerifiedPaymentFact(
                                            original.tenantNo(),
                                            original.eventNo(),
                                            original.orderNo(),
                                            original.paymentNo(),
                                            original.platformMerchantNo(),
                                            original.paid(),
                                            original.paidAt(),
                                            original.verificationReference(),
                                            1000L)));
            assertThrows(
                    MallException.class,
                    () ->
                            f.svc.confirmPayment(
                                    new VerifiedPaymentFact(
                                            original.tenantNo(),
                                            "different-event",
                                            original.orderNo(),
                                            original.paymentNo(),
                                            original.platformMerchantNo(),
                                            original.paid(),
                                            original.paidAt(),
                                            original.verificationReference(),
                                            1000L)));
        }
        assertThrows(IllegalArgumentException.class, () -> PaymentTimePrecision.normalize(0L));
        assertThrows(
                IllegalArgumentException.class,
                () -> PaymentTimePrecision.normalize(Long.MAX_VALUE));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        PaymentTimePrecision.latestMillis(
                                Instant.ofEpochMilli(Long.MAX_VALUE), 1000L));
        assertEquals(
                Long.MAX_VALUE,
                PaymentTimePrecision.latestMillis(Instant.ofEpochMilli(Long.MAX_VALUE), 1L));
    }

    @Test void boundedScheduledRecoveryUsesPersistedIdentifiersAndNeverHoldsTransactionAcrossProvider() throws Exception {
        try(var db=new MysqlDatabase()) {
            var f=setup(db,false);start(f);
            f.bridge.payment =
                    new PaymentOutcome(
                            f.order.orderNo(),
                            Optional.of("pay"),
                            "platform",
                            f.order.payable(),
                            AsyncStatus.SUCCEEDED,
                            Optional.of(Instant.ofEpochMilli(2000)),
                            Optional.empty());
            var recovery=new DefaultMallRecoveryFacade(new JdbcRecoveryRepository(db.ds),f.mall.c.uow,f.svc,null);
            var first=recovery.recover(new MallRecoveryFacade.Batch(0,1));
            assertEquals(1,first.visited());assertEquals(0,first.failed());assertTrue(first.nextOrderId()>0);
            assertEquals(
                    "PAID",
                    db.jdbc.queryForObject("SELECT pay_status FROM mall_order", String.class));
            assertEquals(
                    "COMPLETED",
                    db.jdbc.queryForObject(
                            "SELECT fulfillment_status FROM mall_order", String.class));
            assertTrue(recovery.recover(new MallRecoveryFacade.Batch(first.nextOrderId(),1)).endOfScan());
            assertThrows(IllegalArgumentException.class,()->recovery.recover(new MallRecoveryFacade.Batch(0,501)));
            assertThrows(IllegalStateException.class,()->f.mall.c.uow.required(()->recovery.recover(new MallRecoveryFacade.Batch(0,1))));
        }
    }
    static final StoreRef STORE = MallOrderConcurrencyMysqlTest.STORE;
    static final ActorContext BUYER = MallOrderConcurrencyMysqlTest.BUYER;
    static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(2000), ZoneOffset.UTC);

    static class Bridge implements PlatformPaymentPort, HardwareInventoryPort {
        PaymentIntentCommand intent;
        PaymentQuery lastPaymentQuery;
        PaymentOutcome payment;
        boolean closed;
        boolean unknownInventory;
        int creates, ships, queries, closes, reserves, releases, inventoryQueries;
        boolean shipCrash, inventoryQueryUnknown;
        boolean createThrows;
        InventoryCommand lastShipment;
        final Map<String, InventoryOutcome> operations = new HashMap<>();

        void external() {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        }

        public PaymentOutcome create(PaymentIntentCommand c) {
            external();
            creates++;
            intent = c;
            if (createThrows) throw new IllegalStateException("lost response");
            return payment == null
                    ? new PaymentOutcome(
                            c.orderNo(),
                            Optional.of("pay"),
                            c.platformMerchantNo(),
                            c.amount(),
                            AsyncStatus.PENDING,
                            Optional.empty(),
                            Optional.empty())
                    : payment;
        }

        public PaymentOutcome query(PaymentQuery q) {
            lastPaymentQuery=q;
            external();
            queries++;
            return payment == null
                    ? new PaymentOutcome(
                            q.orderNo(),
                            Optional.of("pay"),
                            "platform",
                            new Money("CNY", 8800),
                            AsyncStatus.UNKNOWN,
                            Optional.empty(),
                            Optional.empty())
                    : payment;
        }

        public PaymentOutcome close(PaymentQuery q) {
            external();
            closes++;
            return new PaymentOutcome(
                    q.orderNo(),
                    Optional.of("pay"),
                    "platform",
                    intent.amount(),
                    AsyncStatus.PENDING,
                    Optional.empty(),
                    Optional.empty(),
                    Map.of(),
                    closed,
                    closed ? Optional.of("provider-fence") : Optional.empty());
        }

        public PaymentRefundOutcome refund(PaymentRefundCommand c) {
            throw new AssertionError();
        }

        public PaymentRefundOutcome queryRefund(PaymentRefundQuery c) {
            throw new AssertionError();
        }

        public InventoryOutcome reserve(InventoryCommand c) {
            external();
            reserves++;
            return inventory(c, false);
        }

        public InventoryOutcome ship(InventoryCommand c) {
            external();
            lastShipment = c;
            if (shipCrash) throw new SimulatedCrash();
            if (!operations.containsKey(c.operationNo())) ships++;
            return inventory(c, true);
        }

        InventoryOutcome inventory(InventoryCommand c, boolean ship) {
            if (unknownInventory)
                return new InventoryOutcome(
                        c.orderComponentNo(),
                        AsyncStatus.UNKNOWN,
                        0,
                        0,
                        Optional.empty(),
                        c.operationNo(),
                        0);
            return operations.computeIfAbsent(
                    c.operationNo(),
                    k ->
                            new InventoryOutcome(
                                    c.orderComponentNo(),
                                    AsyncStatus.SUCCEEDED,
                                    2,
                                    ship ? c.quantity() : 0,
                                    Optional.empty(),
                                    c.operationNo(),
                                    c.quantity()));
        }

        public InventoryOutcome query(InventoryQuery q) {
            external();
            inventoryQueries++;
            if (inventoryQueryUnknown)
                return new InventoryOutcome(
                        q.orderComponentNo(),
                        AsyncStatus.UNKNOWN,
                        0,
                        0,
                        Optional.empty(),
                        q.operationNo(),
                        0);
            return operations.getOrDefault(
                    q.operationNo(),
                    new InventoryOutcome(
                            q.orderComponentNo(),
                            AsyncStatus.UNKNOWN,
                            0,
                            0,
                            Optional.of("NOT_FOUND"),
                            q.operationNo(),
                            0));
        }

        public InventoryOutcome release(InventoryQuery q) {
            external();
            releases++;
            return new InventoryOutcome(
                    q.orderComponentNo(),
                    unknownInventory ? AsyncStatus.UNKNOWN : AsyncStatus.SUCCEEDED,
                    0,
                    0,
                    Optional.empty(),
                    q.operationNo(),
                    0);
        }
    }

    record Fixture(
            MallOrderConcurrencyMysqlTest.Fixture mall,
            CommerceFulfillmentService svc,
            Bridge bridge,
            OrderView order) {}

    Fixture setup(MysqlDatabase db, boolean hardware) {
        return setup(db, hardware, null);
    }

    Fixture setup(
            MysqlDatabase db,
            boolean hardware,
            List<ai.neargo.commerce.api.catalog.SkuComponent> components) {
        return setup(db, hardware, components, m -> {});
    }

    Fixture setup(
            MysqlDatabase db,
            boolean hardware,
            List<ai.neargo.commerce.api.catalog.SkuComponent> components,
            java.util.function.Consumer<MallOrderConcurrencyMysqlTest.Fixture> configure) {
        return setup(db, hardware, components, configure, true);
    }

    Fixture setup(
            MysqlDatabase db,
            boolean hardware,
            List<ai.neargo.commerce.api.catalog.SkuComponent> components,
            java.util.function.Consumer<MallOrderConcurrencyMysqlTest.Fixture> configure,
            boolean migrate) {
        if (migrate) db.migrate();
        var m = new MallOrderConcurrencyMysqlTest.Fixture(db, hardware, components);
        configure.accept(m);
        var events = new JdbcCommerceEventPersistence(db.ds);
        var subscription = new JdbcSubscriptionRepository(db.ds, m.c.ids);
        var schedule = new EndScheduleService(subscription, m.c.uow, events, m.c.ids, CLOCK);
        // Pre-V6 migration fixtures seed historical data, not current V9 source-management columns.
        if (migrate) m.c.uow.required(
                () -> {
                    schedule.persist(
                            subscription.lock(STORE),
                            Set.of("BASE"),
                            "initialize",
                            Instant.ofEpochMilli(1000),
                            Set.of("free"),
                            Map.of(),
                            "buyer");
                    return null;
                });
        var delivery =
                new SubscriptionService(
                        subscription, m.c.repo, m.c.uow, schedule, events, m.c.ids, CLOCK);
        var bridge = new Bridge();
        var svc =
                new CommerceFulfillmentService(
                        new JdbcMallRepository(db.ds, events),
                        new JdbcFulfillmentRepository(db.ds),
                        new JdbcSubscriptionAccountRepository(db.ds),
                        m.c.uow,
                        delivery,
                        bridge,
                        bridge,
                        events,
                        CLOCK,
                        m.c.ids,
                        "platform",
                        Set.of("APP:WECHAT", "OFFLINE:BANK_TRANSFER"),
                        (a, s, c) -> new AuthorizationDecision(true, "OWNER", Optional.empty()));
        return new Fixture(m, svc, bridge, m.place(m.quote(), "place"));
    }

    PaymentOutcome start(Fixture f) {
        return f.svc.startPayment(
                BUYER, new StartPaymentCommand(STORE, f.order.orderNo(), "APP", "WECHAT", null));
    }

    VerifiedPaymentFact paid(Fixture f, long amount) {
        return new VerifiedPaymentFact(
                "tenant-a",
                "paid-event",
                f.order.orderNo(),
                "pay",
                "platform",
                new Money("CNY", amount),
                Instant.ofEpochMilli(2100),
                "signed");
    }

    long count(MysqlDatabase db, String sql) {
        return db.jdbc.queryForObject(sql, Long.class);
    }

    @Test
    void rejectsUnboundAndMismatchedPaidFactsThenDeliversExactlyOnce() {
        try (var db = new MysqlDatabase()) {
            var f = setup(db, false);
            assertThrows(MallException.class, () -> f.svc.confirmPayment(paid(f, 8800)));
            start(f);
            assertThrows(MallException.class, () -> f.svc.confirmPayment(paid(f, 8801)));
            f.svc.confirmPayment(paid(f, 8800));
            f.svc.confirmPayment(
                    new VerifiedPaymentFact(
                            "tenant-a",
                            "replay-event",
                            f.order.orderNo(),
                            "pay",
                            "platform",
                            new Money("CNY", 8800),
                            Instant.ofEpochMilli(2100),
                            "replay-proof"));
            assertEquals(
                    "signed",
                    db.jdbc.queryForObject(
                            "select verified_fact_snapshot->>'$.verificationReference' from"
                                    + " mall_payment_ref",
                            String.class));
            assertEquals(
                    "paid-event",
                    db.jdbc.queryForObject(
                            "select verified_fact_snapshot->>'$.eventNo' from mall_payment_ref",
                            String.class));
            f.svc.fulfill(new FulfillmentQuery("tenant-a", f.order.orderNo()));
            f.svc.fulfill(new FulfillmentQuery("tenant-a", f.order.orderNo()));
            assertEquals(2, count(db, "select count(*) from mch_subscription_period"));
            assertEquals(
                    9000,
                    count(
                            db,
                            "select sum(cash_basis_minor+credit_basis_minor) from"
                                    + " mch_subscription_period"));
            assertEquals(
                    0, count(db, "select reserved_minor from mch_subscription_credit_account"));
            assertEquals(0, count(db, "select balance_minor from mch_subscription_credit_account"));
            assertNull(
                    db.jdbc.queryForObject(
                            "select pending_change_order_no from mch_subscription_account",
                            String.class));
            assertEquals(
                    "COMPLETED",
                    db.jdbc.queryForObject(
                            "select fulfillment_status from mall_order", String.class));
        }
    }

    @Test
    void unknownPaymentAndInventoryNeverReleaseHoldsAndDefinitiveClosureDoes() {
        try (var db = new MysqlDatabase()) {
            var f = setup(db, false);
            start(f);
            f.mall.api.cancelOrder(
                    BUYER, new CancelOrderCommand(STORE, f.order.orderNo(), "cancel", "cancel"));
            f.svc.recover(new FulfillmentQuery("tenant-a", f.order.orderNo()));
            assertEquals(
                    200, count(db, "select reserved_minor from mch_subscription_credit_account"));
            f.bridge.closed = true;
            f.svc.recover(new FulfillmentQuery("tenant-a", f.order.orderNo()));
            assertEquals(
                    "CLOSED",
                    db.jdbc.queryForObject("select pay_status from mall_order", String.class));
            assertEquals(
                    0, count(db, "select reserved_minor from mch_subscription_credit_account"));
            f.svc.confirmPayment(paid(f, 8800));
            assertEquals(
                    "REFUNDING",
                    db.jdbc.queryForObject("select refund_status from mall_order", String.class));
            assertThrows(
                    MallException.class,
                    () -> f.svc.fulfill(new FulfillmentQuery("tenant-a", f.order.orderNo())));
        }
    }

    @Test
    void shipmentUsesExactOperationIdentityAndCannotExceedRemainder() {
        try (var db = new MysqlDatabase()) {
            var f = setup(db, true);
            start(f);
            f.svc.confirmPayment(paid(f, 9000));
            String part =
                    db.jdbc.queryForObject(
                            "select order_component_no from mall_order_component", String.class);
            var first =
                    new HardwareShipmentCommand(
                            "tenant-a",
                            f.order.orderNo(),
                            part,
                            "ship-1",
                            "carrier",
                            "tracking-1",
                            1,"ops-shipping");
            f.svc.confirmShipment(first);
            f.svc.confirmShipment(first);
            assertEquals(1, f.bridge.ships);
            assertEquals("ops-shipping",f.bridge.lastShipment.operatorNo());
            assertThrows(
                    MallException.class,
                    () ->
                            f.svc.confirmShipment(
                                    new HardwareShipmentCommand(
                                            "tenant-a",
                                            f.order.orderNo(),
                                            part,
                                            "ship-2",
                                            "carrier",
                                            "tracking-2",
                                            2)));
            f.svc.confirmShipment(
                    new HardwareShipmentCommand(
                            "tenant-a",
                            f.order.orderNo(),
                            part,
                            "ship-2",
                            "carrier",
                            "tracking-2",
                            1));
            assertEquals(2, f.bridge.ships);
            assertEquals(
                    "COMPLETED",
                    db.jdbc.queryForObject(
                            "select fulfillment_status from mall_order", String.class));
        }
    }

    @Test
    void joinedOuterTransactionRejectsEveryExternalEntryBeforeAnyPortOrIntent() {
        try (var db = new MysqlDatabase()) {
            var f = setup(db, false);
            assertThrows(IllegalStateException.class, () -> f.mall.c.uow.required(() -> start(f)));
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            f.mall.c.uow.required(
                                    () ->
                                            f.svc.recover(
                                                    new FulfillmentQuery(
                                                            "tenant-a", f.order.orderNo()))));
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            f.mall.c.uow.required(
                                    () ->
                                            f.svc.confirmShipment(
                                                    new HardwareShipmentCommand(
                                                            "tenant-a",
                                                            f.order.orderNo(),
                                                            "part",
                                                            "ship",
                                                            "carrier",
                                                            "track",
                                                            1))));
            assertEquals(0, f.bridge.creates);
            assertEquals(0, f.bridge.ships);
            assertEquals(0, count(db, "select count(*) from mall_payment_ref"));
        }
    }

    @Test
    void recoveryReplaysOriginalAttemptAfterDefiniteNotFound() {
        try (var db = new MysqlDatabase()) {
            var f = setup(db, false);
            f.bridge.createThrows = true;
            assertEquals(AsyncStatus.UNKNOWN, start(f).status());
            String attempt = f.bridge.intent.paymentAttemptNo();
            f.bridge.createThrows = false;
            f.bridge.payment =
                    new PaymentOutcome(
                            f.order.orderNo(),
                            Optional.empty(),
                            "platform",
                            f.order.payable(),
                            AsyncStatus.UNKNOWN,
                            Optional.empty(),
                            Optional.of("NOT_FOUND"));
            f.svc.recover(new FulfillmentQuery("tenant-a", f.order.orderNo()));
            assertEquals(2, f.bridge.creates);
            assertEquals(attempt, f.bridge.intent.paymentAttemptNo());
            assertEquals(1, count(db, "select count(*) from mall_payment_ref"));
            assertEquals(
                    200, count(db, "select reserved_minor from mch_subscription_credit_account"));
        }
    }

    @Test
    void queryRecoversUnknownPaidAndValidatesAllCoordinates() {
        try (var db = new MysqlDatabase()) {
            var f = setup(db, false);
            f.bridge.createThrows = true;
            start(f);
            f.bridge.payment =
                    new PaymentOutcome(
                            f.order.orderNo(),
                            Optional.of("pay"),
                            "wrong-platform",
                            f.order.payable(),
                            AsyncStatus.SUCCEEDED,
                            Optional.of(Instant.ofEpochMilli(2100)),
                            Optional.empty());
            assertThrows(
                    MallException.class,
                    () -> f.svc.recover(new FulfillmentQuery("tenant-a", f.order.orderNo())));
            f.bridge.payment =
                    new PaymentOutcome(
                            f.order.orderNo(),
                            Optional.of("pay"),
                            "platform",
                            f.order.payable(),
                            AsyncStatus.SUCCEEDED,
                            Optional.of(Instant.ofEpochMilli(2100)),
                            Optional.empty());
            f.svc.recover(new FulfillmentQuery("tenant-a", f.order.orderNo()));
            assertEquals(
                    "COMPLETED",
                    db.jdbc.queryForObject(
                            "select fulfillment_status from mall_order", String.class));
            assertEquals(2, count(db, "select count(*) from mch_subscription_period"));
            assertThrows(
                    MallException.class,
                    () ->
                            f.svc.confirmPayment(
                                    new VerifiedPaymentFact(
                                            "tenant-a",
                                            "other",
                                            f.order.orderNo(),
                                            "pay",
                                            "platform",
                                            new Money("USD", 8800),
                                            Instant.ofEpochMilli(2100),
                                            "signed")));
        }
    }

    @Test
    void unknownInventoryRetainsCancellationUntilFencedRelease() {
        try (var db = new MysqlDatabase()) {
            var f = setup(db, true);
            f.bridge.unknownInventory = true;
            assertThrows(MallException.class, () -> start(f));
            assertEquals(0, f.bridge.creates);
            f.mall.api.cancelOrder(
                    BUYER, new CancelOrderCommand(STORE, f.order.orderNo(), "cancel", "cancel"));
            f.svc.recover(new FulfillmentQuery("tenant-a", f.order.orderNo()));
            assertEquals(
                    "CANCEL_PENDING",
                    db.jdbc.queryForObject("select pay_status from mall_order", String.class));
            f.bridge.unknownInventory = false;
            f.svc.recover(new FulfillmentQuery("tenant-a", f.order.orderNo()));
            assertEquals(
                    "CLOSED",
                    db.jdbc.queryForObject("select pay_status from mall_order", String.class));
        }
    }

    @Test
    void mixedDigitalGroupRollbackAtFinalHoldCommitThenDeliversWithoutHardwareShipment() {
        try (var db = new MysqlDatabase()) {
            var components =
                    List.of(
                            new ai.neargo.commerce.api.catalog.SkuComponent(
                                    null,
                                    ComponentKind.PLAN,
                                    "pro-1",
                                    null,
                                    null,
                                    30,
                                    2,
                                    50,
                                    false,
                                    "ON_FULFILLMENT"),
                            new ai.neargo.commerce.api.catalog.SkuComponent(
                                    null,
                                    ComponentKind.END_PACKAGE,
                                    null,
                                    "POS",
                                    null,
                                    30,
                                    1,
                                    0,
                                    true,
                                    "ON_FULFILLMENT"),
                            new ai.neargo.commerce.api.catalog.SkuComponent(
                                    null,
                                    ComponentKind.HARDWARE,
                                    null,
                                    null,
                                    "device",
                                    null,
                                    2,
                                    50,
                                    false,
                                    "ON_FULFILLMENT"));
            var f = setup(db, true, components);
            start(f);
            f.svc.confirmPayment(paid(f, 8800));
            db.jdbc.execute(
                    "ALTER TABLE mch_subscription_account ADD CONSTRAINT fail_last_hold CHECK"
                            + " (pending_change_order_no IS NOT NULL)");
            assertThrows(
                    RuntimeException.class,
                    () -> f.svc.fulfill(new FulfillmentQuery("tenant-a", f.order.orderNo())));
            assertEquals(1, count(db, "select count(*) from mch_subscription_period"));
            assertEquals(
                    3,
                    count(
                            db,
                            "select count(*) from mall_fulfillment_task where task_type='DIGITAL'"
                                    + " and status='PENDING'"));
            assertEquals(
                    "PAID",
                    db.jdbc.queryForObject("select pay_status from mall_order", String.class));
            assertEquals(
                    "SUCCEEDED",
                    db.jdbc.queryForObject("select status from mall_payment_ref", String.class));
            assertEquals(1, f.bridge.creates);
            assertEquals(
                    200, count(db, "select reserved_minor from mch_subscription_credit_account"));
            assertEquals(1, count(db, "select entitlement_version from mch_subscription_account"));
            db.jdbc.execute("ALTER TABLE mch_subscription_account DROP CHECK fail_last_hold");
            var r = f.svc.fulfill(new FulfillmentQuery("tenant-a", f.order.orderNo()));
            assertEquals(AsyncStatus.SUCCEEDED, r.digitalStatus());
            assertEquals(AsyncStatus.PENDING, r.hardwareStatus());
            assertEquals(0, f.bridge.ships);
            assertEquals(4, count(db, "select count(*) from mch_subscription_period"));
            assertEquals(
                    3,
                    count(
                            db,
                            "select count(*) from mall_fulfillment_task where task_type='DIGITAL'"
                                    + " and status='SUCCEEDED'"));
            assertEquals(
                    4500,
                    count(
                            db,
                            "select sum(cash_basis_minor+credit_basis_minor) from"
                                    + " mch_subscription_period"));
            f.svc.fulfill(new FulfillmentQuery("tenant-a", f.order.orderNo()));
            assertEquals(4, count(db, "select count(*) from mch_subscription_period"));
            assertEquals(1, f.bridge.creates);
        }
    }

    @Test void canonicalInternalZeroRouteDoesNotDependOnExternalSdkRoutesAndRejectsPositiveCash() {
        try(var db=new MysqlDatabase()) {
            var f =
                    setup(
                            db,
                            false,
                            null,
                            m -> {
                                db.jdbc.update(
                                        "update mch_subscription_credit_account set"
                                            + " balance_minor=9000");
                                db.jdbc.update(
                                        "update mch_subscription_credit_lot set"
                                            + " issued_minor=4500,remaining_minor=4500");
                            });
            var paid =
                    f.svc.startPayment(
                            BUYER,
                            new StartPaymentCommand(
                                    STORE, f.order.orderNo(), "INTERNAL", "ZERO", null));
            assertEquals(AsyncStatus.SUCCEEDED,paid.status());assertEquals(0,f.bridge.creates);
        }
        try(var db=new MysqlDatabase()) {
            var f=setup(db,false);
            assertThrows(
                    MallException.class,
                    () ->
                            f.svc.startPayment(
                                    BUYER,
                                    new StartPaymentCommand(
                                            STORE, f.order.orderNo(), "INTERNAL", "ZERO", null)));
            assertEquals(0,f.bridge.creates);
            assertEquals(0,count(db,"select count(*) from mall_payment_ref"));
        }
    }
    @Test
    void zeroPayableSettlesThroughInternalFactWithNoPaymentPortCall() {
        try (var db = new MysqlDatabase()) {
            var f =
                    setup(
                            db,
                            false,
                            null,
                            m -> {
                                db.jdbc.update(
                                        "update mch_subscription_credit_account set"
                                                + " balance_minor=9000");
                                db.jdbc.update(
                                        "update mch_subscription_credit_lot set"
                                                + " issued_minor=4500,remaining_minor=4500");
                            });
            assertEquals(0, f.order.payable().minor());
            var paid = start(f);
            assertEquals(AsyncStatus.SUCCEEDED, paid.status());
            assertEquals(0, f.bridge.creates);
            assertTrue(paid.paymentNo().orElseThrow().startsWith("zero:"));
            assertTrue(
                    db.jdbc
                            .queryForObject(
                                    "select verified_fact_snapshot->>'$.verificationReference' from"
                                            + " mall_payment_ref",
                                    String.class)
                            .startsWith("INTERNAL_ZERO_PAYMENT:"));
            f.svc.fulfill(new FulfillmentQuery("tenant-a", f.order.orderNo()));
            assertEquals(
                    9000, count(db, "select sum(credit_basis_minor) from mch_subscription_period"));
            assertEquals(0, count(db, "select balance_minor from mch_subscription_credit_account"));
        }
    }

    @Test
    void falselySuccessfulPrepayAndWrongBuyerCannotMarkOrderPaid() {
        try (var db = new MysqlDatabase()) {
            var f = setup(db, false);
            var system = new ActorContext("tenant-a", SubjectKind.SYSTEM, "buyer", null);
            assertThrows(
                    MallException.class,
                    () ->
                            f.svc.startPayment(
                                    system,
                                    new StartPaymentCommand(
                                            STORE, f.order.orderNo(), "APP", "WECHAT", null)));
            assertEquals(0, f.bridge.creates);
            f.bridge.payment =
                    new PaymentOutcome(
                            f.order.orderNo(),
                            Optional.of("pay"),
                            "platform",
                            f.order.payable(),
                            AsyncStatus.SUCCEEDED,
                            Optional.empty(),
                            Optional.empty());
            assertThrows(MallException.class, () -> start(f));
            assertEquals(
                    "PAYING",
                    db.jdbc.queryForObject("select pay_status from mall_order", String.class));
            assertEquals(
                    200, count(db, "select reserved_minor from mch_subscription_credit_account"));
        }
    }

    @Test
    void unknownShipmentCannotUseUnrelatedAggregateSuccessAndRecoversExactOperation() {
        try (var db = new MysqlDatabase()) {
            var f = setup(db, true);
            start(f);
            f.svc.confirmPayment(paid(f, 9000));
            String part =
                    db.jdbc.queryForObject(
                            "select order_component_no from mall_order_component", String.class);
            var ship =
                    new HardwareShipmentCommand(
                            "tenant-a", f.order.orderNo(), part, "ship-1", "carrier", "track-1", 1);
            f.bridge.unknownInventory = true;
            f.svc.confirmShipment(ship);
            assertThrows(
                    MallException.class,
                    () ->
                            f.svc.confirmShipment(
                                    new HardwareShipmentCommand(
                                            "tenant-a",
                                            f.order.orderNo(),
                                            part,
                                            "ship-2",
                                            "carrier",
                                            "track-2",
                                            1)));
            f.bridge.operations.put(
                    f.bridge.lastShipment.operationNo(),
                    new InventoryOutcome(
                            part, AsyncStatus.SUCCEEDED, 2, 2, Optional.empty(), "unrelated", 1));
            assertThrows(
                    MallException.class,
                    () -> f.svc.recover(new FulfillmentQuery("tenant-a", f.order.orderNo())));
            f.bridge.operations.put(
                    f.bridge.lastShipment.operationNo(),
                    new InventoryOutcome(
                            part,
                            AsyncStatus.SUCCEEDED,
                            2,
                            1,
                            Optional.empty(),
                            f.bridge.lastShipment.operationNo(),
                            1));
            f.svc.recover(new FulfillmentQuery("tenant-a", f.order.orderNo()));
            assertEquals(1, f.bridge.ships);
            assertEquals(1, count(db, "select count(*) from mall_shipment where status='SHIPPED'"));
            assertThrows(
                    MallException.class,
                    () ->
                            f.svc.confirmShipment(
                                    new HardwareShipmentCommand(
                                            "tenant-a",
                                            f.order.orderNo(),
                                            part,
                                            "bad",
                                            "carrier",
                                            "track",
                                            (long) Integer.MAX_VALUE + 1)));
            assertEquals(1, f.bridge.ships);
        }
    }

    @Test
    void concurrentDigitalWorkersCommitOneGroup() throws Exception {
        try (var db = new MysqlDatabase();
                var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var f = setup(db, false);
            start(f);
            f.svc.confirmPayment(paid(f, 8800));
            var a =
                    workers.submit(
                            () ->
                                    f.svc.fulfill(
                                            new FulfillmentQuery("tenant-a", f.order.orderNo())));
            var b =
                    workers.submit(
                            () ->
                                    f.svc.fulfill(
                                            new FulfillmentQuery("tenant-a", f.order.orderNo())));
            assertEquals(
                    AsyncStatus.SUCCEEDED,
                    a.get(20, java.util.concurrent.TimeUnit.SECONDS).digitalStatus());
            assertEquals(
                    AsyncStatus.SUCCEEDED,
                    b.get(20, java.util.concurrent.TimeUnit.SECONDS).digitalStatus());
            assertEquals(2, count(db, "select count(*) from mch_subscription_period"));
            assertEquals(
                    1,
                    count(
                            db,
                            "select count(*) from mall_fulfillment_task where task_type='DIGITAL'"
                                    + " and status='SUCCEEDED'"));
            assertEquals(
                    2,
                    count(
                            db,
                            "select count(*) from mch_subscription_credit_ledger where"
                                    + " event_type='CONSUME'"));
        }
    }

    static class SimulatedCrash extends Error {}

    static class MutableClock extends Clock {
        Instant now = CLOCK.instant();

        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        public Instant instant() {
            return now;
        }
    }

    CommerceFulfillmentService coordinator(
            MysqlDatabase db, Fixture f, CommerceUnitOfWork uow, Clock clock) {
        var events = new JdbcCommerceEventPersistence(db.ds);
        var subscription = new JdbcSubscriptionRepository(db.ds, f.mall.c.ids);
        var schedule = new EndScheduleService(subscription, uow, events, f.mall.c.ids, clock);
        var delivery =
                new SubscriptionService(
                        subscription, f.mall.c.repo, uow, schedule, events, f.mall.c.ids, clock);
        return new CommerceFulfillmentService(
                new JdbcMallRepository(db.ds, events),
                new JdbcFulfillmentRepository(db.ds),
                new JdbcSubscriptionAccountRepository(db.ds),
                uow,
                delivery,
                f.bridge,
                f.bridge,
                events,
                clock,
                f.mall.c.ids,
                "platform",
                Set.of("APP:WECHAT", "OFFLINE:BANK_TRANSFER"),
                (a, s, c) -> new AuthorizationDecision(true, "OWNER", Optional.empty()));
    }

    Fixture interruptedZero(MysqlDatabase db, MutableClock clock) {
        var base =
                setup(
                        db,
                        false,
                        null,
                        m -> {
                            db.jdbc.update(
                                    "update mch_subscription_credit_account set"
                                            + " balance_minor=9000");
                            db.jdbc.update(
                                    "update mch_subscription_credit_lot set"
                                            + " issued_minor=4500,remaining_minor=4500");
                        });
        return interruptZeroAtPreparation(db, clock, base);
    }

    Fixture interruptZeroAtPreparation(MysqlDatabase db, MutableClock clock, Fixture base) {
        var interrupted =
                new CommerceUnitOfWork() {
                    boolean armed = true;

                    public void requireOutsideTransaction() {
                        base.mall.c.uow.requireOutsideTransaction();
                    }

                    public <T> T required(java.util.function.Supplier<T> work) {
                        T result = base.mall.c.uow.required(work);
                        if (armed
                                && !TransactionSynchronizationManager.isActualTransactionActive()
                                && count(
                                                db,
                                                "select count(*) from mall_payment_ref where"
                                                        + " amount_minor=0 and outcome_snapshot is"
                                                        + " null")
                                        > 0) {
                            armed = false;
                            throw new SimulatedCrash();
                        }
                        return result;
                    }
                };
        var f =
                new Fixture(
                        base.mall,
                        coordinator(db, base, interrupted, clock),
                        base.bridge,
                        base.order);
        assertThrows(SimulatedCrash.class, () -> start(f));
        assertEquals(
                "PAYING",
                db.jdbc.queryForObject("select pay_status from mall_order", String.class));
        assertEquals(9000, count(db, "select reserved_minor from mch_subscription_credit_account"));
        return f;
    }

    int paymentCalls(Bridge b) {
        return b.creates + b.queries + b.closes;
    }

    @Test
    void interruptedZeroPreparationRecoversAfterDeadlineAtOriginalTimeWithoutPaymentPort() {
        try (var db = new MysqlDatabase()) {
            var clock = new MutableClock();
            var f = interruptedZero(db, clock);
            clock.now = Instant.ofEpochMilli(1_000_000);
            var q = new FulfillmentQuery("tenant-a", f.order.orderNo());
            f.svc.recover(q);
            f.svc.recover(q);
            assertEquals(0, paymentCalls(f.bridge));
            assertEquals(
                    "PAID",
                    db.jdbc.queryForObject("select pay_status from mall_order", String.class));
            assertEquals(
                    "COMPLETED",
                    db.jdbc.queryForObject(
                            "select fulfillment_status from mall_order", String.class));
            assertEquals(
                    CLOCK.instant().toString(),
                    db.jdbc.queryForObject(
                            "select request_snapshot->>'$.payment.preparedAt' from"
                                    + " mall_payment_ref",
                            String.class));
            assertEquals(
                    CLOCK.instant().toString(),
                    db.jdbc.queryForObject(
                            "select verified_fact_snapshot->>'$.paidAt' from mall_payment_ref",
                            String.class));
            assertEquals(2, count(db, "select count(*) from mch_subscription_period"));
            assertEquals(
                    0, count(db, "select reserved_minor from mch_subscription_credit_account"));
        }
    }

    @Test
    void interruptedZeroExplicitCancellationAndLaterRecoveryCannotIssueRights() {
        try (var db = new MysqlDatabase()) {
            var clock = new MutableClock();
            var f = interruptedZero(db, clock);
            f.mall.api.cancelOrder(
                    BUYER,
                    new CancelOrderCommand(
                            STORE,
                            f.order.orderNo(),
                            "cancel-zero",
                            "cancel before local zero completion"));
            clock.now = Instant.ofEpochMilli(1_000_000);
            var q = new FulfillmentQuery("tenant-a", f.order.orderNo());
            f.svc.recover(q);
            f.svc.recover(q);
            assertEquals(0, paymentCalls(f.bridge));
            assertEquals(
                    "CLOSED",
                    db.jdbc.queryForObject("select pay_status from mall_order", String.class));
            assertEquals(1, count(db, "select count(*) from mch_subscription_period"));
            assertEquals(
                    0, count(db, "select reserved_minor from mch_subscription_credit_account"));
            assertEquals(
                    9000, count(db, "select balance_minor from mch_subscription_credit_account"));
            assertEquals(
                    0,
                    count(
                            db,
                            "select count(*) from mall_payment_ref where verified_fact_snapshot is"
                                    + " not null"));
        }
    }

    @Test
    void interruptedZeroWithoutPreparationTimeFailsClosedLocally() {
        try (var db = new MysqlDatabase()) {
            var clock = new MutableClock();
            var f = interruptedZero(db, clock);
            db.jdbc.update(
                    "update mall_payment_ref set"
                        + " request_snapshot=JSON_REMOVE(request_snapshot,'$.payment.preparedAt')");
            var error =
                    assertThrows(
                            MallException.class,
                            () ->
                                    f.svc.recover(
                                            new FulfillmentQuery("tenant-a", f.order.orderNo())));
            assertEquals("ZERO_PAYMENT_TIME_UNPROVEN", error.code());
            f.mall.api.cancelOrder(
                    BUYER,
                    new CancelOrderCommand(
                            STORE, f.order.orderNo(), "cancel-unproven-zero", "cancel"));
            assertEquals(
                    "ZERO_PAYMENT_TIME_UNPROVEN",
                    assertThrows(
                                    MallException.class,
                                    () ->
                                            f.svc.recover(
                                                    new FulfillmentQuery(
                                                            "tenant-a", f.order.orderNo())))
                            .code());
            assertEquals(0, paymentCalls(f.bridge));
            assertEquals(
                    9000, count(db, "select reserved_minor from mch_subscription_credit_account"));
        }
    }

    @Test
    void schedulerAloneReplaysNeverReceivedShipmentOnlyAfterExactNotFound() {
        try (var db = new MysqlDatabase()) {
            var f = setup(db, true);
            start(f);
            f.svc.confirmPayment(paid(f, 9000));
            String part =
                    db.jdbc.queryForObject(
                            "select order_component_no from mall_order_component", String.class);
            f.bridge.shipCrash = true;
            assertThrows(
                    SimulatedCrash.class,
                    () ->
                            f.svc.confirmShipment(
                                    new HardwareShipmentCommand(
                                            "tenant-a",
                                            f.order.orderNo(),
                                            part,
                                            "dispatch-crash",
                                            "carrier",
                                            "tracking",
                                            2)));
            var original = f.bridge.lastShipment;
            f.bridge.shipCrash = false;
            f.bridge.inventoryQueryUnknown = true;
            var q = new FulfillmentQuery("tenant-a", f.order.orderNo());
            f.svc.recover(q);
            assertEquals(0, f.bridge.ships);
            f.bridge.inventoryQueryUnknown = false;
            f.svc.recover(q);
            f.svc.recover(q);
            assertEquals(1, f.bridge.ships);
            assertEquals(original, f.bridge.lastShipment);
            assertEquals(
                    "COMPLETED",
                    db.jdbc.queryForObject(
                            "select fulfillment_status from mall_order", String.class));
            assertEquals(1, count(db, "select count(*) from mall_shipment where status='SHIPPED'"));
            assertEquals(
                    1,
                    count(db, "select count(*) from mall_fulfillment_task where task_type='SHIP'"));
        }
    }

    static String categoryDdl() {
        try (var in =
                MallFulfillmentMysqlTest.class.getResourceAsStream(
                        "/db/commerce/migration/V11__commerce_mall_category.sql")) {
            var sql = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            return sql.substring(0, sql.indexOf(';'));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    void legacyTaskCannotBeIgnored(boolean cancel) {
        try (var db = new MysqlDatabase()) {
            org.flywaydb.core.Flyway.configure()
                    .dataSource(db.ds)
                    .table("commerce_schema_history")
                    .locations("classpath:db/commerce/migration")
                    .target("5")
                    .load()
                    .migrate();
            // Current catalogue code reads mall_category (V11); borrow its DDL for the V5 fixture,
            // then drop it so the real V11 recreates and seeds it from mall_product.
            db.jdbc.execute(categoryDdl());
            var components =
                    List.of(
                            new ai.neargo.commerce.api.catalog.SkuComponent(
                                    null,
                                    ComponentKind.PLAN,
                                    "pro-1",
                                    null,
                                    null,
                                    30,
                                    1,
                                    50,
                                    false,
                                    "ON_FULFILLMENT"),
                            new ai.neargo.commerce.api.catalog.SkuComponent(
                                    null,
                                    ComponentKind.HARDWARE,
                                    null,
                                    null,
                                    "device",
                                    null,
                                    2,
                                    50,
                                    false,
                                    "ON_FULFILLMENT"));
            var f = setup(db, true, components, m -> {}, false);
            String part =
                    db.jdbc.queryForObject(
                            "select order_component_no from mall_order_component where"
                                    + " component_type='HARDWARE'",
                            String.class);
            db.jdbc.update(
                    "INSERT INTO"
                        + " mall_fulfillment_task(tenant_no,created_at,updated_at,version,deleted,task_no,order_component_no,unit_seq,task_type,status,attempt_count,next_retry_at)"
                        + " VALUES"
                        + " ('tenant-a',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),0,0,'legacy-reserve',?,1,'RESERVE','UNKNOWN',1,0)",
                    part);
            db.jdbc.execute("DROP TABLE mall_category");
            db.migrate();
            assertEquals(
                    1,
                    db.jdbc.queryForObject(
                            "select count(*) from mall_category where category_code='subscriptions'",
                            Integer.class));
            assertNull(
                    db.jdbc.queryForObject(
                            "select order_no from mall_fulfillment_task", String.class));
            assertEquals(
                    "LEGACY_UNKNOWN",
                    db.jdbc.queryForObject(
                            "select dispatch_state from mall_fulfillment_task", String.class));
            if (cancel)
                f.mall.api.cancelOrder(
                        BUYER,
                        new CancelOrderCommand(
                                STORE, f.order.orderNo(), "cancel-legacy", "cancel"));
            var error =
                    assertThrows(
                            MallException.class,
                            () -> {
                                if (cancel)
                                    f.svc.recover(
                                            new FulfillmentQuery("tenant-a", f.order.orderNo()));
                                else start(f);
                            });
            assertEquals("LEGACY_TASK_UNPROVEN", error.code());
            assertEquals(
                    0,
                    paymentCalls(f.bridge)
                            + f.bridge.reserves
                            + f.bridge.releases
                            + f.bridge.inventoryQueries
                            + f.bridge.ships);
            assertEquals(
                    200, count(db, "select reserved_minor from mch_subscription_credit_account"));
            assertEquals(
                    f.order.orderNo(),
                    db.jdbc.queryForObject(
                            "select pending_change_order_no from mch_subscription_account",
                            String.class));
            assertEquals(
                    "RESERVED",
                    db.jdbc.queryForObject(
                            "select status from mch_subscription_reservation", String.class));
            assertEquals(1, count(db, "select count(*) from mall_fulfillment_task"));
            assertEquals(
                    "LEGACY_UNKNOWN",
                    db.jdbc.queryForObject(
                            "select dispatch_state from mall_fulfillment_task", String.class));
            assertNull(
                    db.jdbc.queryForObject(
                            "select request_snapshot from mall_fulfillment_task", String.class));
        }
    }

    @Test
    void migratedLegacyTaskBlocksNewReserveWithoutInventingProof() {
        legacyTaskCannotBeIgnored(false);
    }

    @Test
    void migratedLegacyTaskBlocksCancellationWithoutReleasingHolds() {
        legacyTaskCannotBeIgnored(true);
    }

    @Test void trustedRefreshRecordsLateCashAfterClosedOrderWithoutDeliveringRights() {
        try(var db=new MysqlDatabase()) {
            var f=setup(db,false);start(f);f.bridge.closed=true;
            f.mall.api.cancelOrder(
                    BUYER,
                    new CancelOrderCommand(STORE, f.order.orderNo(), "cancel-refresh", "cancel"));
            f.svc.recover(new FulfillmentQuery(STORE.tenantNo(),f.order.orderNo()));
            f.bridge.payment =
                    new PaymentOutcome(
                            f.order.orderNo(),
                            Optional.of("pay"),
                            "platform",
                            f.order.payable(),
                            AsyncStatus.SUCCEEDED,
                            Optional.of(Instant.ofEpochMilli(2100)),
                            Optional.empty());
            f.svc.refresh(new FulfillmentQuery(STORE.tenantNo(),f.order.orderNo()));
            assertEquals(
                    "PAID",
                    db.jdbc.queryForObject("SELECT pay_status FROM mall_order", String.class));
            assertEquals(
                    "REFUNDING",
                    db.jdbc.queryForObject("SELECT refund_status FROM mall_order", String.class));
            assertEquals(1,count(db,"SELECT count(*) FROM mch_subscription_period"));
        }
    }

    @Test void recoveryCarriesOnlyThePersistedOriginalIntentForHostCloseFencing() {
        try(var db=new MysqlDatabase()) {
            var f=setup(db,false);start(f);
            f.svc.recover(new FulfillmentQuery(STORE.tenantNo(),f.order.orderNo()));
            var tree=tools.jackson.databind.json.JsonMapper.builder().build().valueToTree(f.bridge.lastPaymentQuery);
            assertNotNull(
                    tree.get("original"),
                    "recovery must carry the frozen intent, never client-supplied close metadata");
            assertEquals(
                    f.bridge.intent.paymentAttemptNo(),
                    tree.get("original").get("paymentAttemptNo").asString());
        }
    }

    @Test
    void preFixNonzeroAttemptWithoutPreparationTimeRetainsItsRequestHash() {
        try (var db = new MysqlDatabase()) {
            var f = setup(db, false);
            f.bridge.createThrows = true;
            start(f);
            var p = f.bridge.intent;
            var legacy =
                    new PaymentIntentCommand(
                            p.tenantNo(),
                            p.orderNo(),
                            p.platformMerchantNo(),
                            p.amount(),
                            p.expiresAt(),
                            p.paymentAttemptNo(),
                            p.buyerNo(),
                            p.channel(),
                            p.payMethod(),
                            p.payerReference());
            var original =
                    new FulfillmentRepository.Operation(
                            p.paymentAttemptNo(),
                            p.orderNo(),
                            null,
                            "PAYMENT",
                            legacy,
                            null,
                            null,
                            null,
                            null);
            var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
            var tree = mapper.valueToTree(original);
            ((tools.jackson.databind.node.ObjectNode) tree.get("payment")).remove("preparedAt");
            ((tools.jackson.databind.node.ObjectNode) tree.get("payment")).remove("buyerIdentity");
            ((tools.jackson.databind.node.ObjectNode) tree.get("payment")).remove("offline");
            String request = mapper.writeValueAsString(tree);
            String hash = ai.neargo.commerce.core.spi.FactDigest.of(List.of(request));
            db.jdbc.update(
                    "update mall_payment_ref set request_snapshot=?,request_hash=?", request, hash);
            f.bridge.payment =
                    new PaymentOutcome(
                            f.order.orderNo(),
                            Optional.of("pay"),
                            "platform",
                            f.order.payable(),
                            AsyncStatus.SUCCEEDED,
                            Optional.of(Instant.ofEpochMilli(2100)),
                            Optional.empty());
            f.svc.recover(new FulfillmentQuery("tenant-a", f.order.orderNo()));
            assertEquals(
                    hash,
                    db.jdbc.queryForObject(
                            "select request_hash from mall_payment_ref", String.class));
            assertEquals(
                    "COMPLETED",
                    db.jdbc.queryForObject(
                            "select fulfillment_status from mall_order", String.class));
            assertEquals(1, f.bridge.creates);
            assertEquals(1, f.bridge.queries);
        }
    }

    @Test
    void zeroIntentCommittedAcrossRecoveryLookupAndDeadlineStillSettlesAtOriginalTime() {
        try (var db = new MysqlDatabase()) {
            var clock = new MutableClock();
            var base =
                    setup(
                            db,
                            false,
                            null,
                            m -> {
                                db.jdbc.update(
                                        "update mch_subscription_credit_account set"
                                            + " balance_minor=9000");
                                db.jdbc.update(
                                        "update mch_subscription_credit_lot set"
                                            + " issued_minor=4500,remaining_minor=4500");
                            });
            var interleaved =
                    new CommerceUnitOfWork() {
                        boolean armed = true;

                        public void requireOutsideTransaction() {
                            base.mall.c.uow.requireOutsideTransaction();
                        }

                        public <T> T required(java.util.function.Supplier<T> work) {
                            T result = base.mall.c.uow.required(work);
                            if (armed
                                    && !TransactionSynchronizationManager
                                            .isActualTransactionActive()) {
                                armed = false;
                                interruptZeroAtPreparation(db, clock, base);
                                clock.now = Instant.ofEpochMilli(1_000_000);
                            }
                            return result;
                        }
                    };
            var svc = coordinator(db, base, interleaved, clock);
            svc.recover(new FulfillmentQuery("tenant-a", base.order.orderNo()));
            assertEquals(
                    "PAID",
                    db.jdbc.queryForObject("select pay_status from mall_order", String.class));
            assertEquals(
                    CLOCK.instant().toString(),
                    db.jdbc.queryForObject(
                            "select verified_fact_snapshot->>'$.paidAt' from mall_payment_ref",
                            String.class));
            assertEquals(0, paymentCalls(base.bridge));
            assertEquals(2, count(db, "select count(*) from mch_subscription_period"));
        }
    }
}
