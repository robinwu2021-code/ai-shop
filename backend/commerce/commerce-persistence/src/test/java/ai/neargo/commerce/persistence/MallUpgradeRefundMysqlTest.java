package ai.neargo.commerce.persistence;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.commerce.api.catalog.*;
import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.fulfillment.*;
import ai.neargo.commerce.api.mall.*;
import ai.neargo.commerce.api.port.*;
import ai.neargo.commerce.core.access.EndScheduleService;
import ai.neargo.commerce.core.fulfillment.CommerceFulfillmentService;
import ai.neargo.commerce.core.fulfillment.CommerceRefundService;
import ai.neargo.commerce.core.mall.*;
import ai.neargo.commerce.core.spi.*;
import ai.neargo.commerce.core.subscription.SubscriptionService;

import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;

class MallUpgradeRefundMysqlTest {
    static final StoreRef STORE = MallOrderConcurrencyMysqlTest.STORE;
    static final ActorContext BUYER = MallOrderConcurrencyMysqlTest.BUYER;
    static final long DAY = 86400000L, AT = 165 * DAY + 1000;

    @Test
    void exactUpgradeQuoteAndPlacementUseFrozenRemainingValue() {
        for (boolean pendingRefund : List.of(false, true))
            try (var db = new MysqlDatabase()) {
                db.migrate();
                var f =
                        new MallOrderConcurrencyMysqlTest.Fixture(
                                db,
                                false,
                                List.of(
                                        new SkuComponent(
                                                null,
                                                ComponentKind.PLAN,
                                                "chain-1",
                                                null,
                                                null,
                                                365,
                                                1,
                                                100,
                                                false,
                                                "ON_FULFILLMENT")),
                                69900);
                db.jdbc.update("UPDATE mch_subscription_credit_account SET balance_minor=0");
                db.jdbc.update("UPDATE mch_subscription_credit_lot SET remaining_minor=0");
                var sub = new JdbcSubscriptionRepository(db.ds, f.c.ids);
                f.c.uow.required(
                        () -> {
                            sub.insert(
                                    sub.lock(STORE),
                                    new ai.neargo.commerce.core.spi.Period(
                                            "old",
                                            "old-order",
                                            "ORDER",
                                            "RETAIL:PRO",
                                            "pro-1",
                                            "PRO",
                                            1000,
                                            1000,
                                            365 * DAY,
                                            Map.of("POS", "POS"),
                                            36500,
                                            0,
                                            "CNY",
                                            "policy-1",
                                            false),
                                    2,
                                    "buyer");
                            sub.insert(
                                    sub.lock(STORE),
                                    new ai.neargo.commerce.core.spi.Period(
                                            "pack",
                                            "old-pack",
                                            "ORDER",
                                            null,
                                            null,
                                            null,
                                            1000,
                                            1000,
                                            365 * DAY,
                                            Map.of("KDS", "KDS"),
                                            10000,
                                            0,
                                            "CNY",
                                            "FIFO_V1",
                                            false),
                                    3,
                                    "buyer");
                            return null;
                        });
                var events = new JdbcCommerceEventPersistence(db.ds);
                var clock = Clock.fixed(Instant.ofEpochMilli(AT), ZoneOffset.UTC);
                var schedule = new EndScheduleService(sub, f.c.uow, events, f.c.ids, clock);
                f.c.uow.required(
                        () -> {
                            schedule.persist(
                                    sub.lock(STORE),
                                    Set.of("BASE", "POS", "KDS"),
                                    "seed",
                                    Instant.ofEpochMilli(1000),
                                    Set.of("free", "old", "pack"),
                                    Map.of(),
                                    "buyer");
                            return null;
                        });
                var api =
                        new DefaultMallFacade(
                                new JdbcMallRepository(
                                        db.ds, new JdbcCommerceEventPersistence(db.ds)),
                                f.c.repo,
                                new JdbcSubscriptionAccountRepository(db.ds),
                                f.c.uow,
                                s ->
                                        Optional.of(
                                                new StoreSnapshot(
                                                        s,
                                                        "merchant",
                                                        "RETAIL",
                                                        StoreStatus.ACTIVE,
                                                        CommerceModel.STORE_V1)),
                                (a, s, c) ->
                                        new AuthorizationDecision(true, "OWNER", Optional.empty()),
                                Clock.fixed(Instant.ofEpochMilli(AT), ZoneOffset.UTC),
                                f.c.ids);
                var q = api.quoteUpgrade(BUYER, new UpgradeCommand(STORE, "chain-1", "upgrade", 1));
                assertEquals(20000, q.credit().minor());
                assertEquals(49900, q.payable().minor());
                var order =
                        api.placeOrder(
                                BUYER,
                                new PlaceOrderCommand(
                                        q.quoteNo(), "upgrade-place", Optional.empty()));
                assertEquals(49900, order.payable().minor());
                assertEquals(
                        "NORMAL",
                        db.jdbc.queryForObject(
                                "SELECT state FROM mch_subscription_period WHERE period_no='old'",
                                String.class));
                var bridge = new MallFulfillmentMysqlTest.Bridge();
                var fulfillment =
                        new CommerceFulfillmentService(
                                new JdbcMallRepository(db.ds, events),
                                new JdbcFulfillmentRepository(db.ds),
                                new JdbcSubscriptionAccountRepository(db.ds),
                                f.c.uow,
                                new SubscriptionService(
                                        sub, f.c.repo, f.c.uow, schedule, events, f.c.ids, clock),
                                bridge,
                                bridge,
                                events,
                                clock,
                                f.c.ids,
                                "platform",
                                Set.of("APP:WECHAT"),
                                (a, st, cap) ->
                                        new AuthorizationDecision(true, "OWNER", Optional.empty()));
                fulfillment.startPayment(
                        BUYER,
                        new StartPaymentCommand(STORE, order.orderNo(), "APP", "WECHAT", null));
                fulfillment.confirmPayment(
                        new VerifiedPaymentFact(
                                "tenant-a",
                                "paid-upgrade",
                                order.orderNo(),
                                "pay",
                                "platform",
                                new Money("CNY", 49900),
                                clock.instant(),
                                "verified"));
                if (pendingRefund) {
                    var rb = new RefundBridge();
                    var rs =
                            new CommerceRefundService(
                                    new JdbcMallRepository(db.ds, events),
                                    new JdbcRefundRepository(db.ds, f.c.ids),
                                    new JdbcFulfillmentRepository(db.ds),
                                    sub,
                                    new JdbcSubscriptionAccountRepository(db.ds),
                                    schedule,
                                    f.c.uow,
                                    (a, st, cap) ->
                                            new AuthorizationDecision(
                                                    true, "OPS", Optional.empty()),
                                    rb,
                                    clock);
                    var rp =
                            rs.preview(
                                    BUYER,
                                    new RefundPreviewCommand(
                                            STORE, order.orderNo(), "pending-upgrade", List.of()));
                    assertEquals(49900, rp.cashLimit().minor());
                    assertEquals(0, rp.creditLimit().minor());
                    rs.request(
                            BUYER,
                            new RefundCommand(
                                    STORE,
                                    order.orderNo(),
                                    rp.refundNo(),
                                    rp.cashLimit(),
                                    "undelivered upgrade"));
                    rs.approve(
                            f.c.ops,
                            new RefundApprovalCommand(
                                    STORE,
                                    order.orderNo(),
                                    rp.refundNo(),
                                    rp.approvalDigest(),
                                    rp.cashLimit(),
                                    rp.creditLimit(),
                                    "review"));
                    var rq = new RefundQuery("tenant-a", order.orderNo(), rp.refundNo());
                    rs.recover(rq);
                    rb.finalResult(AsyncStatus.SUCCEEDED);
                    rs.recover(rq);
                    assertEquals(
                            "REFUNDED",
                            db.jdbc.queryForObject(
                                    "SELECT refund_status FROM mall_order", String.class));
                    assertEquals(
                            "NORMAL",
                            db.jdbc.queryForObject(
                                    "SELECT state FROM mch_subscription_period WHERE"
                                            + " period_no='old'",
                                    String.class));
                    assertEquals(
                            0,
                            db.jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM mch_subscription_conversion",
                                    Long.class));
                    assertEquals(
                            0,
                            db.jdbc.queryForObject(
                                    "SELECT balance_minor FROM mch_subscription_credit_account",
                                    Long.class));
                    assertEquals(
                            0,
                            db.jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM mch_subscription_credit_ledger WHERE"
                                            + " event_type='ISSUE'",
                                    Long.class));
                    continue;
                }
                db.jdbc.execute(
                        "ALTER TABLE mch_subscription_account ADD CONSTRAINT upgrade_rollback CHECK"
                                + " (pending_change_order_no IS NOT NULL)");
                assertThrows(
                        RuntimeException.class,
                        () ->
                                fulfillment.fulfill(
                                        new FulfillmentQuery("tenant-a", order.orderNo())));
                assertEquals(
                        "NORMAL",
                        db.jdbc.queryForObject(
                                "SELECT state FROM mch_subscription_period WHERE period_no='old'",
                                String.class));
                assertEquals(
                        0,
                        db.jdbc.queryForObject(
                                "SELECT COUNT(*) FROM mch_subscription_conversion", Integer.class));
                assertEquals(
                        0,
                        db.jdbc.queryForObject(
                                "SELECT COUNT(*) FROM mch_subscription_period WHERE"
                                        + " plan_version_no='chain-1'",
                                Integer.class));
                db.jdbc.execute("ALTER TABLE mch_subscription_account DROP CHECK upgrade_rollback");
                fulfillment.fulfill(new FulfillmentQuery("tenant-a", order.orderNo()));
                fulfillment.fulfill(new FulfillmentQuery("tenant-a", order.orderNo()));
                f.c.uow.required(
                        () -> {
                            var access =
                                    new JdbcEndAccessRepository(db.ds)
                                            .lockAndRead(STORE, "POS", "POS", "client");
                            assertFalse(access.revoked());
                            assertTrue(
                                    access.segments().stream()
                                            .noneMatch(
                                                    segment -> segment.sourceNo().equals("old")));
                            assertTrue(
                                    access.segments().stream()
                                            .anyMatch(
                                                    segment ->
                                                            segment.startsAt().toEpochMilli()
                                                                    == AT));
                            return null;
                        });
                assertEquals(
                        "CONVERTED",
                        db.jdbc.queryForObject(
                                "SELECT state FROM mch_subscription_period WHERE period_no='old'",
                                String.class));
                assertEquals(
                        1,
                        db.jdbc.queryForObject(
                                "SELECT COUNT(*) FROM mch_subscription_conversion", Integer.class));
                assertEquals(
                        20000,
                        db.jdbc.queryForObject(
                                "SELECT SUM(issued_minor) FROM mch_subscription_credit_lot WHERE"
                                        + " source_type='CONVERSION'",
                                Long.class));
                assertEquals(
                        0,
                        db.jdbc.queryForObject(
                                "SELECT balance_minor FROM mch_subscription_credit_account",
                                Long.class));
                assertEquals(
                        69900,
                        db.jdbc.queryForObject(
                                "SELECT cash_basis_minor+credit_basis_minor FROM"
                                    + " mch_subscription_period WHERE plan_version_no='chain-1'",
                                Long.class));
                assertEquals(
                        AT,
                        db.jdbc.queryForObject(
                                "SELECT effective_end_at FROM mch_subscription_period WHERE"
                                        + " period_no='old'",
                                Long.class));
                assertEquals(
                        365 * DAY,
                        db.jdbc.queryForObject(
                                "SELECT granted_duration_ms FROM mch_subscription_period WHERE"
                                        + " period_no='old'",
                                Long.class));
                assertEquals(
                        365 * DAY,
                        db.jdbc.queryForObject(
                                "SELECT SUM(end_at-start_at) FROM mch_store_end_grant WHERE"
                                        + " period_no='pack' AND state='NORMAL'",
                                Long.class));
                assertEquals(
                        2,
                        db.jdbc.queryForObject(
                                "SELECT COUNT(*) FROM mch_store_end_grant WHERE period_no='pack'"
                                        + " AND state='NORMAL'",
                                Integer.class));
                assertEquals(
                        AT + 365 * DAY,
                        db.jdbc.queryForObject(
                                "SELECT MAX(start_at) FROM mch_store_end_grant WHERE"
                                        + " period_no='pack' AND state='NORMAL'",
                                Long.class));
                var outward = foundation(db, f.c, clock).store(BUYER, STORE);
                assertEquals("chain-1", outward.subscription().currentPlan().planVersionNo());
                assertEquals(
                        "CONVERTED",
                        outward.subscription().sources().stream()
                                .filter(source -> source.sourceNo().equals("old"))
                                .findFirst()
                                .orElseThrow()
                                .lifecycle());
                db.jdbc.update(
                        "UPDATE mch_subscription_period SET effective_end_at=? WHERE"
                                + " period_no='old'",
                        AT - 1);
                assertThrows(
                        ai.neargo.commerce.api.access.AccessDenied.class,
                        () ->
                                f.c.uow.required(
                                        () ->
                                                new JdbcEndAccessRepository(db.ds)
                                                        .lockAndRead(
                                                                STORE, "POS", "POS", "client")));
                db.jdbc.update(
                        "UPDATE mch_subscription_period SET effective_end_at=? WHERE"
                                + " period_no='old'",
                        AT);
                var refundBridge = new RefundBridge();
                var refunds =
                        new CommerceRefundService(
                                new JdbcMallRepository(db.ds, events),
                                new JdbcRefundRepository(db.ds, f.c.ids),
                                new JdbcFulfillmentRepository(db.ds),
                                sub,
                                new JdbcSubscriptionAccountRepository(db.ds),
                                schedule,
                                f.c.uow,
                                (a, st, cap) ->
                                        new AuthorizationDecision(true, "OPS", Optional.empty()),
                                refundBridge,
                                clock);
                var preview =
                        refunds.preview(
                                BUYER,
                                new RefundPreviewCommand(
                                        STORE,
                                        order.orderNo(),
                                        "upgrade-refund-rejected",
                                        List.of()));
                refunds.request(
                        BUYER,
                        new RefundCommand(
                                STORE,
                                order.orderNo(),
                                preview.refundNo(),
                                preview.cashLimit(),
                                "review"));
                refunds.approve(
                        f.c.ops,
                        new RefundApprovalCommand(
                                STORE,
                                order.orderNo(),
                                preview.refundNo(),
                                preview.approvalDigest(),
                                preview.cashLimit(),
                                preview.creditLimit(),
                                "explicit reviewed quote"));
                assertEquals(
                        365 * DAY,
                        db.jdbc.queryForObject(
                                "SELECT SUM(end_at-start_at) FROM mch_store_end_grant WHERE"
                                        + " period_no='pack' AND state='NORMAL'",
                                Long.class));
                var query = new RefundQuery("tenant-a", order.orderNo(), preview.refundNo());
                refunds.recover(query);
                refundBridge.finalResult(AsyncStatus.FAILED);
                refunds.recover(query);
                assertEquals(
                        AT + 365 * DAY,
                        db.jdbc.queryForObject(
                                "SELECT MAX(start_at) FROM mch_store_end_grant WHERE"
                                        + " period_no='pack' AND state='NORMAL'",
                                Long.class));
                assertEquals(
                        "NORMAL",
                        db.jdbc.queryForObject(
                                "SELECT state FROM mch_subscription_period WHERE"
                                        + " plan_version_no='chain-1'",
                                String.class));
                var again =
                        refunds.preview(
                                BUYER,
                                new RefundPreviewCommand(
                                        STORE,
                                        order.orderNo(),
                                        "upgrade-refund-success",
                                        List.of()));
                refunds.request(
                        BUYER,
                        new RefundCommand(
                                STORE,
                                order.orderNo(),
                                again.refundNo(),
                                again.cashLimit(),
                                "review"));
                refunds.approve(
                        f.c.ops,
                        new RefundApprovalCommand(
                                STORE,
                                order.orderNo(),
                                again.refundNo(),
                                again.approvalDigest(),
                                again.cashLimit(),
                                again.creditLimit(),
                                "approved full allocation"));
                refundBridge.result = null;
                var second = new RefundQuery("tenant-a", order.orderNo(), again.refundNo());
                refunds.recover(second);
                refundBridge.finalResult(AsyncStatus.SUCCEEDED);
                refunds.recover(second);
                assertEquals(
                        20000,
                        db.jdbc.queryForObject(
                                "SELECT balance_minor FROM mch_subscription_credit_account",
                                Long.class));
                assertEquals(
                        "CONVERTED",
                        db.jdbc.queryForObject(
                                "SELECT state FROM mch_subscription_period WHERE period_no='old'",
                                String.class));
                assertEquals(
                        49900,
                        db.jdbc.queryForObject(
                                "SELECT cash_refunded_minor FROM mall_order", Long.class));
                assertEquals(
                        365 * DAY,
                        db.jdbc.queryForObject(
                                "SELECT SUM(end_at-start_at) FROM mch_store_end_grant WHERE"
                                        + " period_no='pack' AND state='NORMAL'",
                                Long.class));
            }
    }

    static class RefundBridge extends MallFulfillmentMysqlTest.Bridge {
        PaymentRefundCommand command;
        PaymentRefundOutcome result;
        int refunds, refundQueries;

        public PaymentRefundOutcome refund(PaymentRefundCommand c) {
            external();
            refunds++;
            command = c;
            return result == null
                    ? new PaymentRefundOutcome(
                            c.refundNo(), AsyncStatus.UNKNOWN, Optional.empty(), Optional.empty())
                    : result;
        }

        public PaymentRefundOutcome queryRefund(PaymentRefundQuery q) {
            external();
            refundQueries++;
            return result;
        }

        VerifiedRefundFact proof(AsyncStatus status) {
            return new VerifiedRefundFact(
                    command.tenantNo(),
                    "refund-event",
                    command.orderNo(),
                    command.refundNo(),
                    command.store(),
                    command.paymentAttemptNo(),
                    command.originalPaymentNo(),
                    command.platformMerchantNo(),
                    command.originalPaid(),
                    command.amount(),
                    "provider-" + command.refundNo(),
                    status,
                    true,
                    Instant.ofEpochMilli(2100),
                    "verified-provider-refund");
        }

        void finalResult(AsyncStatus status) {
            result =
                    new PaymentRefundOutcome(
                            command.refundNo(),
                            status,
                            Optional.of("provider-" + command.refundNo()),
                            Optional.empty(),
                            proof(status));
        }
    }

    CommerceRefundService refundService(
            MysqlDatabase db, MallFulfillmentMysqlTest.Fixture f, RefundBridge bridge) {
        return refundService(db, f, bridge, MallFulfillmentMysqlTest.CLOCK);
    }

    CommerceRefundService refundService(
            MysqlDatabase db,
            MallFulfillmentMysqlTest.Fixture f,
            RefundBridge bridge,
            Clock clock) {
        var sub = new JdbcSubscriptionRepository(db.ds, f.mall().c.ids);
        var events = new JdbcCommerceEventPersistence(db.ds);
        return new CommerceRefundService(
                new JdbcMallRepository(db.ds, events),
                new JdbcRefundRepository(db.ds, f.mall().c.ids),
                new JdbcFulfillmentRepository(db.ds),
                sub,
                new JdbcSubscriptionAccountRepository(db.ds),
                new EndScheduleService(sub, f.mall().c.uow, events, f.mall().c.ids, clock),
                f.mall().c.uow,
                (a, st, cap) ->
                        new AuthorizationDecision(
                                "trusted".equals(a.accountSessionFingerprint()),
                                "OPS",
                                Optional.empty()),
                bridge,
                clock);
    }

    @Test
    void operatorDeclineOnlyChangesRequestedReviewAndNeverUnfreezesDispatchedRefund() {
        try (var db = new MysqlDatabase()) {
            var helper = new MallFulfillmentMysqlTest();
            var f = helper.setup(db, false);
            helper.start(f);
            f.svc().confirmPayment(helper.paid(f, 8800));
            f.svc().fulfill(new FulfillmentQuery("tenant-a", f.order().orderNo()));
            var bridge = new RefundBridge();
            var api = refundService(db, f, bridge);
            var preview = api.preview(BUYER, new RefundPreviewCommand(STORE,
                    f.order().orderNo(), "decline-1", List.of()));
            api.request(BUYER, new RefundCommand(STORE, f.order().orderNo(),
                    preview.refundNo(), preview.cashLimit(), "buyer reason"));
            var command = new RefundRejectionCommand(STORE, f.order().orderNo(),
                    preview.refundNo(), preview.approvalDigest(), "policy evidence");
            String orderBefore =
                    db.jdbc.queryForObject(
                            "SELECT CONCAT(pay_status,':',fulfillment_status,':',refund_status)"
                                + " FROM mall_order",
                            String.class);
            var sourcesBefore =
                    db.jdbc.queryForList(
                            "SELECT period_no,state,version FROM mch_subscription_period ORDER BY"
                                + " period_no");
            assertThrows(MallException.class, () -> api.reject(BUYER, command));
            assertEquals("DECLINED", api.reject(f.mall().c.ops, command).reviewStatus());
            assertEquals(api.reject(f.mall().c.ops, command), api.reject(f.mall().c.ops, command));
            assertEquals(
                    orderBefore,
                    db.jdbc.queryForObject(
                            "SELECT CONCAT(pay_status,':',fulfillment_status,':',refund_status)"
                                + " FROM mall_order",
                            String.class));
            assertEquals(
                    sourcesBefore,
                    db.jdbc.queryForList(
                            "SELECT period_no,state,version FROM mch_subscription_period ORDER BY"
                                + " period_no"));
            assertNull(
                    db.jdbc.queryForObject(
                            "SELECT verified_fact_snapshot FROM mall_refund WHERE"
                                + " refund_no='decline-1'",
                            String.class));
            assertEquals(0, bridge.refunds);
            assertThrows(
                    MallException.class,
                    () ->
                            api.approve(
                                    f.mall().c.ops,
                                    new RefundApprovalCommand(
                                            STORE,
                                            f.order().orderNo(),
                                            preview.refundNo(),
                                            preview.approvalDigest(),
                                            preview.cashLimit(),
                                            preview.creditLimit(),
                                            "policy evidence")));
        }
    }

    @Test
    void independentOpsReviewQuoteAfterExpiryPreservesImmutableBuyerRequest() {
        try (var db = new MysqlDatabase()) {
            var helper = new MallFulfillmentMysqlTest();
            var f = helper.setup(db, false);
            helper.start(f);
            f.svc().confirmPayment(helper.paid(f, 8800));
            f.svc().fulfill(new FulfillmentQuery("tenant-a", f.order().orderNo()));
            var bridge = new RefundBridge();
            var api = refundService(db, f, bridge);
            var preview = api.preview(BUYER, new RefundPreviewCommand(STORE,
                    f.order().orderNo(), "delayed-review", List.of()));
            api.request(BUYER, new RefundCommand(STORE, f.order().orderNo(),
                    preview.refundNo(), preview.cashLimit(), "buyer reason"));
            String original =
                    db.jdbc.queryForObject(
                            "SELECT policy_snapshot FROM mall_refund WHERE"
                                + " refund_no='delayed-review'",
                            String.class);
            var later = refundService(db, f, bridge,
                    Clock.offset(MallFulfillmentMysqlTest.CLOCK, Duration.ofHours(1)));
            var refresh = new RefundReviewQuoteCommand(STORE, f.order().orderNo(), preview.refundNo(),
                    preview.approvalDigest(), "review-1");
            assertThrows(MallException.class, () -> later.reviewQuote(BUYER, refresh));
            var current = later.reviewQuote(f.mall().c.ops, refresh);
            assertEquals(current, later.reviewQuote(f.mall().c.ops, refresh));
            assertTrue(current.preview().cashLimit().minor() <= preview.cashLimit().minor());
            assertEquals(preview.approvalDigest(), current.requestDigest());
            assertNotEquals(preview.approvalDigest(), current.preview().approvalDigest());
            var approved =
                    later.approve(
                            f.mall().c.ops,
                            new RefundApprovalCommand(
                                    STORE,
                                    f.order().orderNo(),
                                    preview.refundNo(),
                                    current.preview().approvalDigest(),
                                    current.preview().cashLimit(),
                                    current.preview().creditLimit(),
                                    "delayed reviewed evidence",
                                    current.reviewQuoteNo()));
            assertEquals("APPROVED", approved.reviewStatus());
            assertEquals(preview, later.preview(BUYER, new RefundPreviewCommand(STORE,
                    f.order().orderNo(), preview.refundNo(), List.of())));
            assertEquals(preview.approvalDigest(), approved.requestDigest());
            assertEquals(
                    original,
                    db.jdbc.queryForObject(
                            "SELECT policy_snapshot FROM mall_refund WHERE"
                                + " refund_no='delayed-review'",
                            String.class));
            assertThrows(MallException.class, () -> later.reject(f.mall().c.ops,
                    new RefundRejectionCommand(STORE, f.order().orderNo(), preview.refundNo(),
                            preview.approvalDigest(), "cannot override approved")));
            later.recover(new RefundQuery("tenant-a", f.order().orderNo(), preview.refundNo()));
            assertEquals(1, bridge.refunds);
            assertThrows(MallException.class, () -> later.reject(f.mall().c.ops,
                    new RefundRejectionCommand(STORE, f.order().orderNo(), preview.refundNo(),
                            preview.approvalDigest(), "cannot override unknown")));
            bridge.finalResult(AsyncStatus.SUCCEEDED);
            var result =
                    later.recover(
                            new RefundQuery("tenant-a", f.order().orderNo(), preview.refundNo()));
            assertEquals(AsyncStatus.SUCCEEDED, result.status());
            assertEquals(preview.approvalDigest(), result.requestDigest());
            assertEquals(current.reviewQuoteNo(), result.reviewQuoteNo());
        }
    }

    @Test
    void fullReviewEvidenceBoundariesRemainImmutableThroughRecovery() {
        for (int size : List.of(1000, 1001, 1024)) {
            try (var db = new MysqlDatabase()) {
                var helper = new MallFulfillmentMysqlTest();
                var f = helper.setup(db, false);
                helper.start(f);
                f.svc().confirmPayment(helper.paid(f, 8800));
                f.svc().fulfill(new FulfillmentQuery("tenant-a", f.order().orderNo()));
                var bridge = new RefundBridge();
                var refunds = refundService(db, f, bridge);
                var preview =
                        refunds.preview(
                                BUYER,
                                new RefundPreviewCommand(
                                        STORE, f.order().orderNo(), "evidence", List.of()));
                refunds.request(
                        BUYER,
                        new RefundCommand(
                                STORE,
                                f.order().orderNo(),
                                "evidence",
                                preview.cashLimit(),
                                "request"));
                String evidence = "核".repeat(size);
                var approval =
                        new RefundApprovalCommand(
                                STORE,
                                f.order().orderNo(),
                                "evidence",
                                preview.approvalDigest(),
                                preview.cashLimit(),
                                preview.creditLimit(),
                                evidence);
                refunds.approve(f.mall().c.ops, approval);
                refunds.recover(new RefundQuery("tenant-a", f.order().orderNo(), "evidence"));
                assertEquals(evidence, bridge.command.reason());
                assertEquals(
                        evidence,
                        db.jdbc.queryForObject(
                                "SELECT review_evidence FROM mall_refund WHERE"
                                    + " refund_no='evidence'",
                                String.class));
                refunds.approve(f.mall().c.ops, approval);
                assertThrows(
                        MallException.class,
                        () ->
                                refunds.approve(
                                        f.mall().c.ops,
                                        new RefundApprovalCommand(
                                                STORE,
                                                f.order().orderNo(),
                                                "evidence",
                                                preview.approvalDigest(),
                                                preview.cashLimit(),
                                                preview.creditLimit(),
                                                "changed")));
                refunds.recover(new RefundQuery("tenant-a", f.order().orderNo(), "evidence"));
                assertEquals(1, bridge.refunds);
            }
        }
    }

    @Test
    void refundRequestNeverTreatsBuyerAmountAsPaymentAuthority() {
        try (var db = new MysqlDatabase()) {
            var helper = new MallFulfillmentMysqlTest();
            var f = helper.setup(db, false);
            helper.start(f);
            f.svc().confirmPayment(helper.paid(f, 8800));
            f.svc().fulfill(new FulfillmentQuery("tenant-a", f.order().orderNo()));
            var bridge = new RefundBridge();
            var refunds = refundService(db, f, bridge);
            var preview =
                    refunds.preview(
                            BUYER,
                            new RefundPreviewCommand(
                                    STORE, f.order().orderNo(), "refund-1", List.of()));
            assertTrue(preview.requiresManualReview());
            assertThrows(
                    MallException.class,
                    () ->
                            refunds.request(
                                    BUYER,
                                    new RefundCommand(
                                            STORE,
                                            f.order().orderNo(),
                                            "refund-1",
                                            new Money("CNY", 8801),
                                            "request")));
            assertEquals(
                    AsyncStatus.PENDING,
                    refunds.request(
                                    BUYER,
                                    new RefundCommand(
                                            STORE,
                                            f.order().orderNo(),
                                            "refund-1",
                                            new Money("CNY", 8800),
                                            "request"))
                            .status());
            assertEquals(0, bridge.refunds);
            var approval =
                    new RefundApprovalCommand(
                            STORE,
                            f.order().orderNo(),
                            "refund-1",
                            preview.approvalDigest(),
                            preview.cashLimit(),
                            preview.creditLimit(),
                            "reviewed original allocations");
            assertThrows(MallException.class, () -> refunds.approve(BUYER, approval));
            assertThrows(
                    MallException.class,
                    () ->
                            refunds.approve(
                                    new ActorContext(
                                            "tenant-a", SubjectKind.OPERATOR, "buyer", "trusted"),
                                    approval));
            assertThrows(
                    MallException.class,
                    () ->
                            refunds.approve(
                                    new ActorContext(
                                            "tenant-a", SubjectKind.OPERATOR, "ops", "untrusted"),
                                    approval));
            refunds.approve(f.mall().c.ops, approval);
            f.mall()
                    .c
                    .uow
                    .required(
                            () -> {
                                var states =
                                        new JdbcSubscriptionRepository(db.ds, f.mall().c.ids)
                                                .grantStates(STORE);
                                assertTrue(states.containsKey("POS"));
                                assertFalse(states.get("POS").revoked());
                                assertTrue(
                                        new JdbcEndAccessRepository(db.ds)
                                                .lockAndRead(STORE, "POS", "POS", "client")
                                                .segments()
                                                .isEmpty());
                                return null;
                            });
            var visible =
                    foundation(db, f.mall().c, MallFulfillmentMysqlTest.CLOCK).store(BUYER, STORE);
            assertEquals("FREE", visible.subscription().currentPlan().tier());
            assertTrue(
                    visible.subscription().sources().stream()
                            .anyMatch(
                                    source ->
                                            "FROZEN".equals(source.lifecycle())
                                                    && source.revokedDurationMs() == 365 * DAY));
            assertEquals(
                    "request",
                    db.jdbc.queryForObject(
                            "SELECT reason FROM mall_refund WHERE refund_no='refund-1'",
                            String.class));
            assertEquals(
                    "reviewed original allocations",
                    db.jdbc.queryForObject(
                            "SELECT review_evidence FROM mall_refund WHERE refund_no='refund-1'",
                            String.class));
            assertEquals(
                    1,
                    db.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM mch_subscription_period WHERE state='FROZEN'",
                            Integer.class));
            var query = new RefundQuery("tenant-a", f.order().orderNo(), "refund-1");
            assertEquals(AsyncStatus.UNKNOWN, refunds.recover(query).status());
            bridge.result =
                    new PaymentRefundOutcome(
                            "refund-1",
                            AsyncStatus.SUCCEEDED,
                            Optional.of("legacy-unverified"),
                            Optional.empty());
            assertEquals(AsyncStatus.UNKNOWN, refunds.recover(query).status());
            assertEquals(
                    0,
                    db.jdbc.queryForObject(
                            "SELECT balance_minor FROM mch_subscription_credit_account",
                            Long.class));
            var good = bridge.proof(AsyncStatus.SUCCEEDED);
            assertThrows(
                    MallException.class,
                    () ->
                            refunds.confirmRefund(
                                    new VerifiedRefundFact(
                                            good.tenantNo(),
                                            good.eventNo(),
                                            good.orderNo(),
                                            good.refundNo(),
                                            good.store(),
                                            good.paymentAttemptNo(),
                                            "wrong-original",
                                            good.platformMerchantNo(),
                                            good.originalPaid(),
                                            good.refunded(),
                                            good.providerRefundNo(),
                                            good.status(),
                                            true,
                                            good.observedAt(),
                                            good.verificationReference())));
            bridge.finalResult(AsyncStatus.SUCCEEDED);
            db.jdbc.execute(
                    "ALTER TABLE mch_subscription_account ADD CONSTRAINT refund_rollback CHECK"
                            + " (pending_change_order_no IS NOT NULL)");
            assertThrows(RuntimeException.class, () -> refunds.recover(query));
            assertEquals(
                    1,
                    db.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM mch_subscription_period WHERE state='FROZEN'",
                            Integer.class));
            assertEquals(
                    0,
                    db.jdbc.queryForObject(
                            "SELECT cash_refunded_minor FROM mall_order", Long.class));
            db.jdbc.execute("ALTER TABLE mch_subscription_account DROP CHECK refund_rollback");
            assertEquals(AsyncStatus.SUCCEEDED, refunds.recover(query).status());
            refunds.recover(query);
            refunds.confirmRefund(bridge.proof(AsyncStatus.SUCCEEDED));
            assertEquals(1, bridge.refunds);
            assertEquals(
                    200,
                    db.jdbc.queryForObject(
                            "SELECT balance_minor FROM mch_subscription_credit_account",
                            Long.class));
            assertEquals(
                    8800,
                    db.jdbc.queryForObject(
                            "SELECT cash_refunded_minor FROM mall_order", Long.class));
            assertEquals(
                    1,
                    db.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM mch_subscription_period WHERE state='REFUNDED'",
                            Integer.class));
            assertNull(
                    db.jdbc.queryForObject(
                            "SELECT pending_change_order_no FROM mch_subscription_account",
                            String.class));
        }
    }

    @Test
    void latePaymentRefundReleasesOnlyTheOriginalUnclosedHold() {
        latePayment(false);
    }

    @Test
    void lateClosedPaymentRefundPreservesNewerOrderHoldAndNeverMintsCredit() {
        latePayment(true);
    }

    void latePayment(boolean closed) {
        try (var db = new MysqlDatabase()) {
            var helper = new MallFulfillmentMysqlTest();
            var f = helper.setup(db, false);
            helper.start(f);
            f.mall()
                    .api
                    .cancelOrder(
                            BUYER,
                            new CancelOrderCommand(STORE, f.order().orderNo(), "cancel", "cancel"));
            String newer = null;
            if (closed) {
                f.bridge().closed = true;
                f.svc().recover(new FulfillmentQuery("tenant-a", f.order().orderNo()));
                newer = f.mall().place(f.mall().quote(), "new-order").orderNo();
            }
            if (closed) f.svc().confirmPayment(helper.paid(f, 8800));
            else
                helper.coordinator(
                                db,
                                f,
                                f.mall().c.uow,
                                Clock.fixed(Instant.ofEpochMilli(1000000), ZoneOffset.UTC))
                        .confirmPayment(
                                new VerifiedPaymentFact(
                                        "tenant-a",
                                        "late-paid",
                                        f.order().orderNo(),
                                        "pay",
                                        "platform",
                                        new Money("CNY", 8800),
                                        Instant.ofEpochMilli(1000000),
                                        "verified-late"));
            var bridge = new RefundBridge();
            var refunds = refundService(db, f, bridge);
            var query = new FulfillmentQuery("tenant-a", f.order().orderNo());
            assertEquals(AsyncStatus.UNKNOWN, refunds.recoverLatePayment(query).status());
            assertEquals(
                    200,
                    db.jdbc.queryForObject(
                            "SELECT reserved_minor FROM mch_subscription_credit_account",
                            Long.class));
            if (!closed) {
                String rejectedNo = bridge.command.refundNo();
                bridge.finalResult(AsyncStatus.FAILED);
                assertEquals(
                        AsyncStatus.FAILED,
                        refunds.recover(
                                        new RefundQuery(
                                                "tenant-a", f.order().orderNo(), rejectedNo))
                                .status());
                assertEquals(
                        200,
                        db.jdbc.queryForObject(
                                "SELECT reserved_minor FROM mch_subscription_credit_account",
                                Long.class));
                bridge.result = null;
                assertEquals(AsyncStatus.UNKNOWN, refunds.recoverLatePayment(query).status());
                assertNotEquals(rejectedNo, bridge.command.refundNo());
            }
            bridge.finalResult(AsyncStatus.SUCCEEDED);
            assertEquals(AsyncStatus.SUCCEEDED, refunds.recoverLatePayment(query).status());
            refunds.recoverLatePayment(query);
            assertEquals(closed ? 1 : 2, bridge.refunds);
            assertEquals(
                    200,
                    db.jdbc.queryForObject(
                            "SELECT balance_minor FROM mch_subscription_credit_account",
                            Long.class));
            assertEquals(
                    closed ? 200 : 0,
                    db.jdbc.queryForObject(
                            "SELECT reserved_minor FROM mch_subscription_credit_account",
                            Long.class));
            assertEquals(
                    newer,
                    db.jdbc.queryForObject(
                            "SELECT pending_change_order_no FROM mch_subscription_account",
                            String.class));
            assertEquals(
                    1,
                    db.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM mch_subscription_period", Integer.class));
            assertEquals(
                    8800,
                    db.jdbc.queryForObject(
                            "SELECT cash_refunded_minor FROM mall_order WHERE order_no=?",
                            Long.class,
                            f.order().orderNo()));
        }
    }

    @Test
    void concurrentRefundDispatchCreatesOneExternalAttemptAndLegacyQuoteJsonStillPays()
            throws Exception {
        try (var db = new MysqlDatabase()) {
            var helper = new MallFulfillmentMysqlTest();
            var f = helper.setup(db, false);
            db.jdbc.update(
                    "UPDATE mall_quote SET"
                        + " line_snapshot=JSON_REMOVE(line_snapshot,'$.conversions','$.convertedCredit')");
            helper.start(f);
            f.svc().confirmPayment(helper.paid(f, 8800));
            f.svc().fulfill(new FulfillmentQuery("tenant-a", f.order().orderNo()));
            var bridge = new RefundBridge();
            var refunds = refundService(db, f, bridge);
            var p =
                    refunds.preview(
                            BUYER,
                            new RefundPreviewCommand(
                                    STORE, f.order().orderNo(), "parallel-refund", List.of()));
            refunds.request(
                    BUYER,
                    new RefundCommand(
                            STORE, f.order().orderNo(), p.refundNo(), p.cashLimit(), "request"));
            refunds.approve(
                    f.mall().c.ops,
                    new RefundApprovalCommand(
                            STORE,
                            f.order().orderNo(),
                            p.refundNo(),
                            p.approvalDigest(),
                            p.cashLimit(),
                            p.creditLimit(),
                            "review"));
            var start = new java.util.concurrent.CountDownLatch(1);
            var q = new RefundQuery("tenant-a", f.order().orderNo(), p.refundNo());
            try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
                var a =
                        pool.submit(
                                () -> {
                                    start.await();
                                    return refunds.recover(q);
                                });
                var b =
                        pool.submit(
                                () -> {
                                    start.await();
                                    return refunds.recover(q);
                                });
                start.countDown();
                a.get();
                b.get();
            }
            assertEquals(1, bridge.refunds);
            bridge.finalResult(AsyncStatus.SUCCEEDED);
            refunds.recover(q);
            assertEquals(
                    200,
                    db.jdbc.queryForObject(
                            "SELECT balance_minor FROM mch_subscription_credit_account",
                            Long.class));
        }
    }

    @Test
    void selectingFutureCycleRestoresItsOwnLotProvenanceOnly() {
        try (var db = new MysqlDatabase()) {
            var helper = new MallFulfillmentMysqlTest();
            var f =
                    helper.setup(
                            db,
                            false,
                            List.of(
                                    new SkuComponent(
                                            null,
                                            ComponentKind.PLAN,
                                            "pro-1",
                                            null,
                                            null,
                                            365,
                                            2,
                                            100,
                                            false,
                                            "ON_FULFILLMENT")));
            helper.start(f);
            f.svc().confirmPayment(helper.paid(f, 8800));
            f.svc().fulfill(new FulfillmentQuery("tenant-a", f.order().orderNo()));
            String second =
                    db.jdbc.queryForObject(
                            "SELECT period_no FROM mch_subscription_period WHERE"
                                + " plan_version_no='pro-1' ORDER BY original_start_at DESC LIMIT"
                                + " 1",
                            String.class);
            var bridge = new RefundBridge();
            var refunds = refundService(db, f, bridge);
            var p =
                    refunds.preview(
                            BUYER,
                            new RefundPreviewCommand(
                                    STORE, f.order().orderNo(), "cycle-refund", List.of(second)));
            assertEquals(4400, p.cashLimit().minor());
            assertEquals(100, p.creditLimit().minor());
            refunds.request(
                    BUYER,
                    new RefundCommand(
                            STORE,
                            f.order().orderNo(),
                            p.refundNo(),
                            p.cashLimit(),
                            "future cycle",
                            List.of(second)));
            refunds.approve(
                    f.mall().c.ops,
                    new RefundApprovalCommand(
                            STORE,
                            f.order().orderNo(),
                            p.refundNo(),
                            p.approvalDigest(),
                            p.cashLimit(),
                            p.creditLimit(),
                            "source-specific review"));
            var q = new RefundQuery("tenant-a", f.order().orderNo(), p.refundNo());
            refunds.recover(q);
            bridge.finalResult(AsyncStatus.SUCCEEDED);
            refunds.recover(q);
            assertEquals(
                    0,
                    db.jdbc.queryForObject(
                            "SELECT remaining_minor FROM mch_subscription_credit_lot WHERE"
                                    + " credit_lot_no='lot-1'",
                            Long.class));
            assertEquals(
                    100,
                    db.jdbc.queryForObject(
                            "SELECT remaining_minor FROM mch_subscription_credit_lot WHERE"
                                    + " credit_lot_no='lot-2'",
                            Long.class));
            assertEquals(
                    "PARTIAL",
                    db.jdbc.queryForObject("SELECT refund_status FROM mall_order", String.class));
            assertEquals(
                    "PARTIALLY_REFUNDED",
                    f.mall()
                            .api
                            .order(BUYER, new OrderQuery(STORE, f.order().orderNo()))
                            .status()
                            .name());
            String first =
                    db.jdbc.queryForObject(
                            "SELECT period_no FROM mch_subscription_period WHERE"
                                    + " plan_version_no='pro-1' AND state='NORMAL'",
                            String.class);
            var rejected =
                    refunds.preview(
                            BUYER,
                            new RefundPreviewCommand(
                                    STORE,
                                    f.order().orderNo(),
                                    "rejected-after-partial",
                                    List.of(first)));
            approve(f, refunds, rejected, List.of(first));
            bridge.result = null;
            var rejectedQuery =
                    new RefundQuery("tenant-a", f.order().orderNo(), rejected.refundNo());
            refunds.recover(rejectedQuery);
            bridge.finalResult(AsyncStatus.FAILED);
            refunds.recover(rejectedQuery);
            assertEquals(
                    "PARTIAL",
                    db.jdbc.queryForObject("SELECT refund_status FROM mall_order", String.class));
            var finalPreview =
                    refunds.preview(
                            BUYER,
                            new RefundPreviewCommand(
                                    STORE, f.order().orderNo(), "final-cycle", List.of(first)));
            refunds.request(
                    BUYER,
                    new RefundCommand(
                            STORE,
                            f.order().orderNo(),
                            finalPreview.refundNo(),
                            finalPreview.cashLimit(),
                            "remaining cycle",
                            List.of(first)));
            refunds.approve(
                    f.mall().c.ops,
                    new RefundApprovalCommand(
                            STORE,
                            f.order().orderNo(),
                            finalPreview.refundNo(),
                            finalPreview.approvalDigest(),
                            finalPreview.cashLimit(),
                            finalPreview.creditLimit(),
                            "review"));
            bridge.result = null;
            var finalQuery =
                    new RefundQuery("tenant-a", f.order().orderNo(), finalPreview.refundNo());
            refunds.recover(finalQuery);
            bridge.finalResult(AsyncStatus.SUCCEEDED);
            refunds.recover(finalQuery);
            refunds.recover(q);
            refunds.recover(finalQuery);
            assertEquals(
                    "REFUNDED",
                    db.jdbc.queryForObject("SELECT refund_status FROM mall_order", String.class));
            assertEquals(
                    8800,
                    db.jdbc.queryForObject(
                            "SELECT cash_refunded_minor FROM mall_order", Long.class));
            assertEquals(
                    200,
                    db.jdbc.queryForObject(
                            "SELECT balance_minor FROM mch_subscription_credit_account",
                            Long.class));
        }
    }

    @Test
    void existingBalance80000Pays69900AndKeeps10100WithoutAnExternalCashAttempt() {
        try (var db = new MysqlDatabase()) {
            db.migrate();
            var f = new MallOrderConcurrencyMysqlTest.Fixture(db, false, null, 69900);
            db.jdbc.update("UPDATE mch_subscription_credit_account SET balance_minor=80000");
            db.jdbc.update(
                    "UPDATE mch_subscription_credit_lot SET"
                            + " issued_minor=40000,remaining_minor=40000");
            var sub = new JdbcSubscriptionRepository(db.ds, f.c.ids);
            var events = new JdbcCommerceEventPersistence(db.ds);
            var clock = MallFulfillmentMysqlTest.CLOCK;
            var schedule = new EndScheduleService(sub, f.c.uow, events, f.c.ids, clock);
            f.c.uow.required(
                    () -> {
                        schedule.persist(
                                sub.lock(STORE),
                                Set.of("BASE"),
                                "initialize",
                                Instant.ofEpochMilli(1000),
                                Set.of("free"),
                                Map.of(),
                                "buyer");
                        return null;
                    });
            var quote = f.quote();
            assertTrue(
                    java.util.Arrays.stream(QuoteView.class.getRecordComponents())
                            .anyMatch(c -> c.getName().equals("creditBreakdown")));
            try {
                var breakdown = QuoteView.class.getMethod("creditBreakdown").invoke(quote);
                assertNotNull(breakdown);
                var values =
                        Map.of(
                                "newConversion",
                                0L,
                                "historicalAvailable",
                                80000L,
                                "applied",
                                69900L,
                                "reservedHistorical",
                                69900L,
                                "balanceAfter",
                                10100L);
                for (var value : values.entrySet())
                    assertEquals(value.getValue().longValue(), ((Money) breakdown.getClass().getMethod(value.getKey()).invoke(breakdown)).minor());
                assertEquals(
                        Instant.ofEpochMilli(2000),
                        breakdown.getClass().getMethod("valuationAt").invoke(breakdown));
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            var order = f.place(quote, "balance-purchase");
            assertEquals(0, order.payable().minor());
            var bridge = new RefundBridge();
            var fulfillment =
                    new CommerceFulfillmentService(
                            new JdbcMallRepository(db.ds, events),
                            new JdbcFulfillmentRepository(db.ds),
                            new JdbcSubscriptionAccountRepository(db.ds),
                            f.c.uow,
                            new SubscriptionService(
                                    sub, f.c.repo, f.c.uow, schedule, events, f.c.ids, clock),
                            bridge,
                            bridge,
                            events,
                            clock,
                            f.c.ids,
                            "platform",
                            Set.of("APP:WECHAT"),
                            (a, st, cap) ->
                                    new AuthorizationDecision(true, "OWNER", Optional.empty()));
            fulfillment.startPayment(
                    BUYER, new StartPaymentCommand(STORE, order.orderNo(), "APP", "WECHAT", null));
            fulfillment.fulfill(new FulfillmentQuery("tenant-a", order.orderNo()));
            assertEquals(
                    10100,
                    db.jdbc.queryForObject(
                            "SELECT balance_minor FROM mch_subscription_credit_account",
                            Long.class));
            assertEquals(0, bridge.creates);
            var refunds =
                    new CommerceRefundService(
                            new JdbcMallRepository(db.ds, events),
                            new JdbcRefundRepository(db.ds, f.c.ids),
                            new JdbcFulfillmentRepository(db.ds),
                            sub,
                            new JdbcSubscriptionAccountRepository(db.ds),
                            schedule,
                            f.c.uow,
                            (a, st, cap) ->
                                    new AuthorizationDecision(true, "OPS", Optional.empty()),
                            bridge,
                            clock);
            String refundNo = "r".repeat(64);
            var p =
                    refunds.preview(
                            BUYER,
                            new RefundPreviewCommand(STORE, order.orderNo(), refundNo, List.of()));
            refunds.request(
                    BUYER,
                    new RefundCommand(
                            STORE, order.orderNo(), refundNo, p.cashLimit(), "zero cash"));
            refunds.approve(
                    f.c.ops,
                    new RefundApprovalCommand(
                            STORE,
                            order.orderNo(),
                            refundNo,
                            p.approvalDigest(),
                            p.cashLimit(),
                            p.creditLimit(),
                            "reviewed zero cash"));
            assertEquals(
                    AsyncStatus.SUCCEEDED,
                    refunds.recover(new RefundQuery("tenant-a", order.orderNo(), refundNo))
                            .status());
            assertEquals(0, bridge.refunds);
            assertEquals(
                    80000,
                    db.jdbc.queryForObject(
                            "SELECT balance_minor FROM mch_subscription_credit_account",
                            Long.class));
        }
    }

    @Test
    void approvalUsesOneCutInstantWithAnAdvancingTrustedClock() {
        try (var db = new MysqlDatabase()) {
            var helper = new MallFulfillmentMysqlTest();
            var f = helper.setup(db, false);
            helper.start(f);
            f.svc().confirmPayment(helper.paid(f, 8800));
            f.svc().fulfill(new FulfillmentQuery("tenant-a", f.order().orderNo()));
            var clock =
                    new Clock() {
                        long now = 2000;

                        public ZoneId getZone() {
                            return ZoneOffset.UTC;
                        }

                        public Clock withZone(ZoneId zone) {
                            return this;
                        }

                        public Instant instant() {
                            return Instant.ofEpochMilli(now++);
                        }
                    };
            var sub = new JdbcSubscriptionRepository(db.ds, f.mall().c.ids);
            var events = new JdbcCommerceEventPersistence(db.ds);
            var refunds =
                    new CommerceRefundService(
                            new JdbcMallRepository(db.ds, events),
                            new JdbcRefundRepository(db.ds, f.mall().c.ids),
                            new JdbcFulfillmentRepository(db.ds),
                            sub,
                            new JdbcSubscriptionAccountRepository(db.ds),
                            new EndScheduleService(
                                    sub, f.mall().c.uow, events, f.mall().c.ids, clock),
                            f.mall().c.uow,
                            (a, st, cap) ->
                                    new AuthorizationDecision(true, "OPS", Optional.empty()),
                            new RefundBridge(),
                            clock);
            var p =
                    refunds.preview(
                            BUYER,
                            new RefundPreviewCommand(
                                    STORE, f.order().orderNo(), "one-cut", List.of()));
            refunds.request(
                    BUYER,
                    new RefundCommand(
                            STORE, f.order().orderNo(), p.refundNo(), p.cashLimit(), "request"));
            assertEquals(
                    AsyncStatus.PENDING,
                    refunds.approve(
                                    f.mall().c.ops,
                                    new RefundApprovalCommand(
                                            STORE,
                                            f.order().orderNo(),
                                            p.refundNo(),
                                            p.approvalDigest(),
                                            p.cashLimit(),
                                            p.creditLimit(),
                                            "review"))
                            .status());
            assertEquals(
                    db.jdbc.queryForObject(
                            "SELECT effective_end_at FROM mch_subscription_period WHERE"
                                    + " state='FROZEN'",
                            Long.class),
                    db.jdbc.queryForObject(
                            "SELECT MAX(end_at) FROM mch_store_end_grant WHERE"
                                    + " entitlement_code='POS' AND state='NORMAL'",
                            Long.class));
        }
    }

    ai.neargo.commerce.core.foundation.FoundationQueryService foundation(
            MysqlDatabase db, JdbcCatalogFixture c, Clock clock) {
        return new ai.neargo.commerce.core.foundation.FoundationQueryService(
                c.repo,
                new JdbcSubscriptionRepository(db.ds, c.ids),
                c.uow,
                new ai.neargo.commerce.api.foundation.FoundationAuthorityPort() {
                    public Instant account(ActorContext actor, Instant now) {
                        return now.plusSeconds(3600);
                    }

                    public Member member(ActorContext actor, StoreRef store, Instant now) {
                        return new Member(
                                new StoreSnapshot(
                                        store,
                                        "merchant",
                                        "RETAIL",
                                        StoreStatus.ACTIVE,
                                        CommerceModel.STORE_V1),
                                "ACTIVE",
                                "ACTIVE",
                                true,
                                List.of("OWNER"),
                                true,
                                now.plusSeconds(3600));
                    }
                },
                clock);
    }

    @Test
    void previewReadsCommittedFinancialSourcesAfterAnOlderMvccSnapshot() throws Exception {
        try (var db = new MysqlDatabase();
                var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var helper = new MallFulfillmentMysqlTest();
            var f = helper.setup(db, false);
            helper.start(f);
            f.svc().confirmPayment(helper.paid(f, 8800));
            var refunds = refundService(db, f, new RefundBridge());
            f.mall()
                    .c
                    .uow
                    .required(
                            () -> {
                                assertEquals(
                                        1,
                                        db.jdbc.queryForObject(
                                                "SELECT COUNT(*) FROM mch_subscription_period",
                                                Integer.class));
                                try {
                                    pool.submit(
                                                    () ->
                                                            f.svc()
                                                                    .fulfill(
                                                                            new FulfillmentQuery(
                                                                                    "tenant-a",
                                                                                    f.order()
                                                                                            .orderNo())))
                                            .get(20, java.util.concurrent.TimeUnit.SECONDS);
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                                var p =
                                        refunds.preview(
                                                BUYER,
                                                new RefundPreviewCommand(
                                                        STORE,
                                                        f.order().orderNo(),
                                                        "current-read",
                                                        List.of()));
                                assertEquals(8800, p.cashLimit().minor());
                                assertEquals(200, p.creditLimit().minor());
                                return null;
                            });
        }
    }

    @Test
    void pendingDigitalGroupRefundRetainsHoldsUntilVerifiedAndCannotRegrant() {
        try (var db = new MysqlDatabase()) {
            var helper = new MallFulfillmentMysqlTest();
            var f = helper.setup(db, false);
            helper.start(f);
            var paid = helper.paid(f, 8800);
            f.svc().confirmPayment(paid);
            var bridge = new RefundBridge();
            var refunds = refundService(db, f, bridge);
            var p =
                    refunds.preview(
                            BUYER,
                            new RefundPreviewCommand(
                                    STORE, f.order().orderNo(), "pending-group", List.of()));
            assertEquals(8800, p.cashLimit().minor());
            assertEquals(0, p.creditLimit().minor());
            assertTrue(p.requiresManualReview());
            refunds.request(
                    BUYER,
                    new RefundCommand(
                            STORE,
                            f.order().orderNo(),
                            p.refundNo(),
                            p.cashLimit(),
                            "undelivered"));
            refunds.approve(
                    f.mall().c.ops,
                    new RefundApprovalCommand(
                            STORE,
                            f.order().orderNo(),
                            p.refundNo(),
                            p.approvalDigest(),
                            p.cashLimit(),
                            p.creditLimit(),
                            "review"));
            f.svc().confirmPayment(paid);
            assertThrows(
                    MallException.class,
                    () -> f.svc().fulfill(new FulfillmentQuery("tenant-a", f.order().orderNo())));
            assertEquals(
                    0,
                    db.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM mch_subscription_period WHERE"
                                    + " source_type='ORDER'",
                            Long.class));
            assertEquals(
                    "FROZEN",
                    db.jdbc.queryForObject(
                            "SELECT status FROM mall_fulfillment_task WHERE task_type='DIGITAL'",
                            String.class));
            var q = new RefundQuery("tenant-a", f.order().orderNo(), p.refundNo());
            refunds.recover(q);
            assertEquals(
                    200,
                    db.jdbc.queryForObject(
                            "SELECT reserved_minor FROM mch_subscription_credit_account",
                            Long.class));
            bridge.finalResult(AsyncStatus.SUCCEEDED);
            refunds.recover(q);
            refunds.recover(q);
            f.svc().recover(new FulfillmentQuery("tenant-a", f.order().orderNo()));
            assertEquals(
                    "REFUNDED",
                    db.jdbc.queryForObject("SELECT refund_status FROM mall_order", String.class));
            assertEquals(
                    "CANCELLED",
                    db.jdbc.queryForObject(
                            "SELECT status FROM mall_fulfillment_task WHERE task_type='DIGITAL'",
                            String.class));
            assertEquals(
                    200,
                    db.jdbc.queryForObject(
                            "SELECT balance_minor FROM mch_subscription_credit_account",
                            Long.class));
            assertEquals(
                    0,
                    db.jdbc.queryForObject(
                            "SELECT reserved_minor FROM mch_subscription_credit_account",
                            Long.class));
            assertEquals(
                    0,
                    db.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM mch_subscription_credit_ledger WHERE event_type"
                                    + " IN ('RESTORE','CONSUME')",
                            Long.class));
        }
    }

    @Test
    void deliveredDigitalRefundDoesNotWaitForOrCancelHardwareShipment() {
        try (var db = new MysqlDatabase()) {
            var helper = new MallFulfillmentMysqlTest();
            var f =
                    helper.setup(
                            db,
                            true,
                            List.of(
                                    new SkuComponent(
                                            null,
                                            ComponentKind.PLAN,
                                            "pro-1",
                                            null,
                                            null,
                                            365,
                                            1,
                                            50,
                                            false,
                                            "ON_FULFILLMENT"),
                                    new SkuComponent(
                                            null,
                                            ComponentKind.HARDWARE,
                                            null,
                                            null,
                                            "hw",
                                            null,
                                            1,
                                            50,
                                            false,
                                            "ON_FULFILLMENT")));
            helper.start(f);
            f.svc().confirmPayment(helper.paid(f, f.order().payable().minor()));
            f.svc().fulfill(new FulfillmentQuery("tenant-a", f.order().orderNo()));
            var bridge = new RefundBridge();
            var refunds = refundService(db, f, bridge);
            var p =
                    refunds.preview(
                            BUYER,
                            new RefundPreviewCommand(
                                    STORE, f.order().orderNo(), "digital-only", List.of()));
            assertEquals(4300, p.cashLimit().minor());
            refunds.request(
                    BUYER,
                    new RefundCommand(
                            STORE, f.order().orderNo(), p.refundNo(), p.cashLimit(), "digital"));
            refunds.approve(
                    f.mall().c.ops,
                    new RefundApprovalCommand(
                            STORE,
                            f.order().orderNo(),
                            p.refundNo(),
                            p.approvalDigest(),
                            p.cashLimit(),
                            p.creditLimit(),
                            "review"));
            String hardware =
                    db.jdbc.queryForObject(
                            "SELECT order_component_no FROM mall_order_component WHERE"
                                    + " component_type='HARDWARE'",
                            String.class);
            f.svc()
                    .confirmShipment(
                            new HardwareShipmentCommand(
                                    "tenant-a",
                                    f.order().orderNo(),
                                    hardware,
                                    "ship-after-refund",
                                    "carrier",
                                    "tracking",
                                    1));
            assertEquals(1, f.bridge().ships);
            assertEquals(0, f.bridge().releases);
            var q = new RefundQuery("tenant-a", f.order().orderNo(), p.refundNo());
            refunds.recover(q);
            bridge.finalResult(AsyncStatus.SUCCEEDED);
            refunds.recover(q);
            assertEquals(
                    "PARTIAL",
                    db.jdbc.queryForObject("SELECT refund_status FROM mall_order", String.class));
        }
    }

    @Test
    void internalLateRefundNamespaceRejectsCaseAndUnicodeAliases() {
        try (var db = new MysqlDatabase()) {
            var helper = new MallFulfillmentMysqlTest();
            var f = helper.setup(db, false);
            helper.start(f);
            f.svc().confirmPayment(helper.paid(f, 8800));
            f.svc().fulfill(new FulfillmentQuery("tenant-a", f.order().orderNo()));
            var refunds = refundService(db, f, new RefundBridge());
            for (String no : List.of("late-abc-0", "LATE-abc-0", "làte-abc-0"))
                assertEquals(
                        "INVALID_REQUEST",
                        assertThrows(
                                        MallException.class,
                                        () ->
                                                refunds.preview(
                                                        BUYER,
                                                        new RefundPreviewCommand(
                                                                STORE,
                                                                f.order().orderNo(),
                                                                no,
                                                                List.of())))
                                .code());
        }
    }

    @Test
    void failedPendingGroupRefundRestoresExactTasksAndOriginalOccupancy() {
        try (var db = new MysqlDatabase()) {
            var helper = new MallFulfillmentMysqlTest();
            var f = helper.setup(db, false);
            helper.start(f);
            f.svc().confirmPayment(helper.paid(f, 8800));
            var bridge = new RefundBridge();
            var refunds = refundService(db, f, bridge);
            db.jdbc.update(
                    "UPDATE mall_fulfillment_task SET dispatch_state='LEGACY_UNKNOWN' WHERE"
                            + " task_type='DIGITAL'");
            assertEquals(
                    "INVALID_TASK_GROUP",
                    assertThrows(
                                    MallException.class,
                                    () ->
                                            refunds.preview(
                                                    BUYER,
                                                    new RefundPreviewCommand(
                                                            STORE,
                                                            f.order().orderNo(),
                                                            "bad-group",
                                                            List.of())))
                            .code());
            db.jdbc.update(
                    "UPDATE mall_fulfillment_task SET dispatch_state='LOCAL_PENDING' WHERE"
                            + " task_type='DIGITAL'");
            var p =
                    refunds.preview(
                            BUYER,
                            new RefundPreviewCommand(
                                    STORE, f.order().orderNo(), "failed-group", List.of()));
            approve(f, refunds, p, List.of());
            var q = new RefundQuery("tenant-a", f.order().orderNo(), p.refundNo());
            refunds.recover(q);
            bridge.finalResult(AsyncStatus.FAILED);
            refunds.recover(q);
            assertEquals(
                    f.order().orderNo(),
                    db.jdbc.queryForObject(
                            "SELECT pending_change_order_no FROM mch_subscription_account",
                            String.class));
            assertEquals(
                    200,
                    db.jdbc.queryForObject(
                            "SELECT reserved_minor FROM mch_subscription_credit_account",
                            Long.class));
            assertEquals(
                    "PENDING",
                    db.jdbc.queryForObject(
                            "SELECT status FROM mall_fulfillment_task WHERE task_type='DIGITAL'",
                            String.class));
            assertEquals(
                    AsyncStatus.SUCCEEDED,
                    f.svc()
                            .fulfill(new FulfillmentQuery("tenant-a", f.order().orderNo()))
                            .digitalStatus());
        }
    }

    void approve(
            MallFulfillmentMysqlTest.Fixture f,
            CommerceRefundService refunds,
            RefundPreviewView p,
            List<String> sources) {
        refunds.request(
                BUYER,
                new RefundCommand(
                        STORE,
                        f.order().orderNo(),
                        p.refundNo(),
                        p.cashLimit(),
                        "request",
                        sources));
        refunds.approve(
                f.mall().c.ops,
                new RefundApprovalCommand(
                        STORE,
                        f.order().orderNo(),
                        p.refundNo(),
                        p.approvalDigest(),
                        p.cashLimit(),
                        p.creditLimit(),
                        "review"));
    }

    @Test
    void lateWholeOrderRefundRecoversUnknownHardwareReleaseWithoutShipping() {
        try (var db = new MysqlDatabase()) {
            var helper = new MallFulfillmentMysqlTest();
            var f = helper.setup(db, true);
            helper.start(f);
            helper.coordinator(
                            db,
                            f,
                            f.mall().c.uow,
                            Clock.fixed(Instant.ofEpochMilli(1000000), ZoneOffset.UTC))
                    .confirmPayment(
                            new VerifiedPaymentFact(
                                    "tenant-a",
                                    "late-hardware",
                                    f.order().orderNo(),
                                    "pay",
                                    "platform",
                                    f.order().payable(),
                                    Instant.ofEpochMilli(1000000),
                                    "verified"));
            var bridge = new RefundBridge();
            var refunds = refundService(db, f, bridge);
            var q = new FulfillmentQuery("tenant-a", f.order().orderNo());
            refunds.recoverLatePayment(q);
            bridge.finalResult(AsyncStatus.SUCCEEDED);
            refunds.recoverLatePayment(q);
            f.bridge().unknownInventory = true;
            f.svc().recover(q);
            assertEquals(1, f.bridge().releases);
            assertEquals(
                    "UNKNOWN",
                    db.jdbc.queryForObject(
                            "SELECT status FROM mall_fulfillment_task WHERE task_type='RELEASE'",
                            String.class));
            f.bridge().unknownInventory = false;
            f.svc().recover(q);
            f.svc().recover(q);
            assertEquals(2, f.bridge().releases);
            assertEquals(0, f.bridge().ships);
            assertEquals(
                    "SUCCEEDED",
                    db.jdbc.queryForObject(
                            "SELECT status FROM mall_fulfillment_task WHERE task_type='RELEASE'",
                            String.class));
        }
    }

    @Test
    void zeroCashSourceRefundAndRemainingGiftStayPartialUntilAllSourcesCancelled() {
        for (boolean gift : List.of(false, true))
            try (var db = new MysqlDatabase()) {
                var helper = new MallFulfillmentMysqlTest();
                var components =
                        gift
                                ? List.of(
                                        new SkuComponent(
                                                null,
                                                ComponentKind.PLAN,
                                                "pro-1",
                                                null,
                                                null,
                                                365,
                                                1,
                                                100,
                                                false,
                                                "ON_FULFILLMENT"),
                                        new SkuComponent(
                                                null,
                                                ComponentKind.END_PACKAGE,
                                                null,
                                                "KDS",
                                                null,
                                                10,
                                                1,
                                                0,
                                                true,
                                                "ON_FULFILLMENT"))
                                : List.of(
                                        new SkuComponent(
                                                null,
                                                ComponentKind.PLAN,
                                                "pro-1",
                                                null,
                                                null,
                                                365,
                                                2,
                                                100,
                                                false,
                                                "ON_FULFILLMENT"));
                var f =
                        helper.setup(
                                db,
                                false,
                                components,
                                m -> {
                                    db.jdbc.update(
                                            "UPDATE mch_subscription_credit_account SET"
                                                    + " balance_minor=9000");
                                    db.jdbc.update(
                                            "UPDATE mch_subscription_credit_lot SET"
                                                    + " issued_minor=4500,remaining_minor=4500");
                                });
                helper.start(f);
                f.svc().fulfill(new FulfillmentQuery("tenant-a", f.order().orderNo()));
                String source =
                        db.jdbc.queryForObject(
                                "SELECT period_no FROM mch_subscription_period WHERE"
                                        + " plan_version_no='pro-1' ORDER BY original_start_at DESC"
                                        + " LIMIT 1",
                                String.class);
                var bridge = new RefundBridge();
                var refunds = refundService(db, f, bridge);
                var p =
                        refunds.preview(
                                BUYER,
                                new RefundPreviewCommand(
                                        STORE,
                                        f.order().orderNo(),
                                        "partial-zero",
                                        List.of(source)));
                approve(f, refunds, p, List.of(source));
                refunds.recover(new RefundQuery("tenant-a", f.order().orderNo(), p.refundNo()));
                assertEquals(
                        "PARTIAL",
                        db.jdbc.queryForObject(
                                "SELECT refund_status FROM mall_order", String.class));
                String last =
                        db.jdbc.queryForObject(
                                "SELECT period_no FROM mch_subscription_period WHERE"
                                        + " source_type='ORDER' AND state='NORMAL'",
                                String.class);
                var end =
                        refunds.preview(
                                BUYER,
                                new RefundPreviewCommand(
                                        STORE, f.order().orderNo(), "last-zero", List.of(last)));
                approve(f, refunds, end, List.of(last));
                refunds.recover(new RefundQuery("tenant-a", f.order().orderNo(), end.refundNo()));
                assertEquals(
                        "REFUNDED",
                        db.jdbc.queryForObject(
                                "SELECT refund_status FROM mall_order", String.class));
                assertEquals(0, bridge.refunds);
            }
    }

    @Test
    void staleDeliveredPreviewCannotEraseAnInterveningPartialRefund() {
        try (var db = new MysqlDatabase()) {
            var helper = new MallFulfillmentMysqlTest();
            var f = twoCycleOrder(db, helper);
            var bridge = new RefundBridge();
            var clock = Clock.fixed(Instant.ofEpochMilli(3000 + 365 * DAY), ZoneOffset.UTC);
            var refunds = refundService(db, f, bridge, clock);
            var sources =
                    db.jdbc.queryForList(
                            "SELECT period_no FROM mch_subscription_period WHERE"
                                    + " source_type='ORDER' ORDER BY original_start_at",
                            String.class);
            var stale =
                    refunds.preview(
                            BUYER,
                            new RefundPreviewCommand(
                                    STORE,
                                    f.order().orderNo(),
                                    "stale-future",
                                    List.of(sources.get(1))));
            refunds.request(
                    BUYER,
                    new RefundCommand(
                            STORE,
                            f.order().orderNo(),
                            stale.refundNo(),
                            stale.cashLimit(),
                            "future",
                            List.of(sources.get(1))));
            String frozen =
                    db.jdbc.queryForObject(
                            "SELECT policy_snapshot FROM mall_refund WHERE"
                                    + " refund_no='stale-future'",
                            String.class);
            var first =
                    refunds.preview(
                            BUYER,
                            new RefundPreviewCommand(
                                    STORE,
                                    f.order().orderNo(),
                                    "expired-first",
                                    List.of(sources.get(0))));
            assertEquals(0, first.cashLimit().minor());
            approve(f, refunds, first, List.of(sources.get(0)));
            refunds.recover(new RefundQuery("tenant-a", f.order().orderNo(), first.refundNo()));
            assertEquals(
                    "PARTIAL",
                    db.jdbc.queryForObject("SELECT refund_status FROM mall_order", String.class));
            assertEquals(
                    "VERSION_CONFLICT",
                    assertThrows(
                                    MallException.class,
                                    () ->
                                            refunds.approve(
                                                    f.mall().c.ops,
                                                    new RefundApprovalCommand(
                                                            STORE,
                                                            f.order().orderNo(),
                                                            stale.refundNo(),
                                                            stale.approvalDigest(),
                                                            stale.cashLimit(),
                                                            stale.creditLimit(),
                                                            "stale review")))
                            .code());
            assertEquals(
                    AsyncStatus.PENDING,
                    refunds.recover(
                                    new RefundQuery(
                                            "tenant-a", f.order().orderNo(), stale.refundNo()))
                            .status());
            assertEquals(
                    "PARTIAL",
                    db.jdbc.queryForObject("SELECT refund_status FROM mall_order", String.class));
            assertEquals(
                    "REQUESTED",
                    db.jdbc.queryForObject(
                            "SELECT status FROM mall_refund WHERE refund_no='stale-future'",
                            String.class));
            assertEquals(
                    frozen,
                    db.jdbc.queryForObject(
                            "SELECT policy_snapshot FROM mall_refund WHERE"
                                    + " refund_no='stale-future'",
                            String.class));
            assertEquals(
                    "NORMAL",
                    db.jdbc.queryForObject(
                            "SELECT state FROM mch_subscription_period WHERE period_no=?",
                            String.class,
                            sources.get(1)));
            assertNull(
                    db.jdbc.queryForObject(
                            "SELECT pending_change_order_no FROM mch_subscription_account",
                            String.class));
            assertEquals(0, bridge.refunds);
        }
    }

    MallFulfillmentMysqlTest.Fixture twoCycleOrder(
            MysqlDatabase db, MallFulfillmentMysqlTest helper) {
        var f =
                helper.setup(
                        db,
                        false,
                        List.of(
                                new SkuComponent(
                                        null,
                                        ComponentKind.PLAN,
                                        "pro-1",
                                        null,
                                        null,
                                        365,
                                        2,
                                        100,
                                        false,
                                        "ON_FULFILLMENT")));
        helper.start(f);
        f.svc().confirmPayment(helper.paid(f, 8800));
        f.svc().fulfill(new FulfillmentQuery("tenant-a", f.order().orderNo()));
        return f;
    }

    @Test
    void retiredNullCutFailsBothOutwardValidatorsWhileActivePermanentNullEndRemainsValid() {
        try (var db = new MysqlDatabase()) {
            var helper = new MallFulfillmentMysqlTest();
            var f = twoCycleOrder(db, helper);
            var clock = Clock.fixed(Instant.ofEpochMilli(3000 + 365 * DAY), ZoneOffset.UTC);
            var refunds = refundService(db, f, new RefundBridge(), clock);
            var sub = new JdbcSubscriptionRepository(db.ds, f.mall().c.ids);
            String source =
                    db.jdbc.queryForObject(
                            "SELECT period_no FROM mch_subscription_period WHERE"
                                    + " source_type='ORDER' ORDER BY original_start_at LIMIT 1",
                            String.class);
            var preview =
                    refunds.preview(
                            BUYER,
                            new RefundPreviewCommand(
                                    STORE, f.order().orderNo(), "expired-cut", List.of(source)));
            approve(f, refunds, preview, List.of(source));
            refunds.recover(new RefundQuery("tenant-a", f.order().orderNo(), preview.refundNo()));
            assertFalse(
                    f.mall()
                            .c
                            .uow
                            .required(
                                    () ->
                                            new JdbcEndAccessRepository(db.ds)
                                                    .lockAndRead(STORE, "POS", "POS", "client"))
                            .revoked());
            assertEquals(
                    1,
                    f.mall()
                            .c
                            .uow
                            .required(
                                    () ->
                                            new JdbcEndAccessRepository(db.ds)
                                                    .lockAndRead(STORE, "POS", "POS", "client"))
                            .segments()
                            .size());
            assertTrue(
                    f.mall()
                            .c
                            .uow
                            .required(
                                    () -> {
                                        sub.lock(STORE);
                                        return sub.grantStates(STORE);
                                    })
                            .containsKey("BASE"));
            assertNull(
                    db.jdbc.queryForObject(
                            "SELECT effective_end_at FROM mch_subscription_period WHERE"
                                    + " period_no='free'",
                            Long.class));
            var permanent =
                    f.mall()
                            .c
                            .uow
                            .required(
                                    () ->
                                            new JdbcEndAccessRepository(db.ds)
                                                    .lockAndRead(STORE, "BASE", "BASE", "client"));
            assertFalse(permanent.revoked());
            assertTrue(
                    permanent.segments().stream()
                            .anyMatch(
                                    segment ->
                                            segment.sourceNo().equals("free")
                                                    && segment.endsAt() == null));
            Long cut =
                    db.jdbc.queryForObject(
                            "SELECT effective_end_at FROM mch_subscription_period WHERE"
                                    + " period_no=?",
                            Long.class,
                            source);
            db.jdbc.update(
                    "UPDATE mch_subscription_period SET effective_end_at=NULL WHERE period_no=?",
                    source);
            var denied =
                    assertThrows(
                            ai.neargo.commerce.api.access.AccessDenied.class,
                            () ->
                                    f.mall()
                                            .c
                                            .uow
                                            .required(
                                                    () ->
                                                            new JdbcEndAccessRepository(db.ds)
                                                                    .lockAndRead(
                                                                            STORE, "POS", "POS",
                                                                            "client")));
            assertEquals(
                    ai.neargo.commerce.api.access.AccessStatus.CHECK_UNAVAILABLE, denied.status());
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            f.mall()
                                    .c
                                    .uow
                                    .required(
                                            () -> {
                                                sub.lock(STORE);
                                                return sub.grantStates(STORE);
                                            }));
            db.jdbc.update(
                    "UPDATE mch_subscription_period SET effective_end_at=? WHERE period_no=?",
                    cut,
                    source);
            assertFalse(
                    f.mall()
                            .c
                            .uow
                            .required(
                                    () ->
                                            new JdbcEndAccessRepository(db.ds)
                                                    .lockAndRead(STORE, "POS", "POS", "client"))
                            .revoked());
            assertTrue(
                    f.mall()
                            .c
                            .uow
                            .required(
                                    () -> {
                                        sub.lock(STORE);
                                        return sub.grantStates(STORE);
                                    })
                            .containsKey("BASE"));
        }
    }
}
