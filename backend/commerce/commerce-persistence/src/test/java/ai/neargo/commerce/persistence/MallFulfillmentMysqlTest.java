package ai.neargo.commerce.persistence;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.fulfillment.*;
import ai.neargo.commerce.api.mall.*;
import ai.neargo.commerce.api.port.*;
import ai.neargo.commerce.core.access.*;
import ai.neargo.commerce.core.fulfillment.*;
import ai.neargo.commerce.core.subscription.*;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.*;
import java.util.*;

class MallFulfillmentMysqlTest {
    static final StoreRef STORE = MallOrderConcurrencyMysqlTest.STORE;
    static final ActorContext BUYER = MallOrderConcurrencyMysqlTest.BUYER;
    static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(2000), ZoneOffset.UTC);

    static class Bridge implements PlatformPaymentPort, HardwareInventoryPort {
        PaymentIntentCommand intent;
        PaymentOutcome payment;
        boolean closed;
        boolean unknownInventory;
        int creates, ships;
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
            external();
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
            return inventory(c, false);
        }

        public InventoryOutcome ship(InventoryCommand c) {
            external();
            lastShipment = c;
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
        db.migrate();
        var m = new MallOrderConcurrencyMysqlTest.Fixture(db, hardware, components);
        configure.accept(m);
        var events = new JdbcCommerceEventPersistence(db.ds);
        var subscription = new JdbcSubscriptionRepository(db.ds, m.c.ids);
        var schedule = new EndScheduleService(subscription, m.c.uow, events, m.c.ids, CLOCK);
        m.c.uow.required(
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
                        Set.of("APP:WECHAT"),
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
                            1);
            f.svc.confirmShipment(first);
            f.svc.confirmShipment(first);
            assertEquals(1, f.bridge.ships);
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
}
