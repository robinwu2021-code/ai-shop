package ai.neargo.commerce.persistence;

import static org.junit.jupiter.api.Assertions.*;
import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.fulfillment.*;
import ai.neargo.commerce.api.port.*;
import ai.neargo.commerce.api.offline.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;

class MallOfflineDeadlineMysqlTest {
    @Test
    void offlinePaymentAfterQuoteExpiryBeforeFrozenDueDateDeliversWithoutRepricing() {
        try (var db = new MysqlDatabase()) {
            var helper = new MallFulfillmentMysqlTest();
            var f = helper.setup(db, false);
            freezeOffline(db, 86400250);
            var svc = helper.coordinator(db, f, f.mall().c.uow,
                    Clock.fixed(Instant.ofEpochMilli(1001000), ZoneOffset.UTC));
            var started = svc.startPayment(MallOrderConcurrencyMysqlTest.BUYER,
                    new StartPaymentCommand(MallOrderConcurrencyMysqlTest.STORE, f.order().orderNo(), "OFFLINE", "BANK_TRANSFER", null));
            assertEquals(AsyncStatus.PENDING, started.status());
            f.bridge().payment = new PaymentOutcome(f.order().orderNo(), Optional.of("pay"), "platform",
                    f.order().payable(), AsyncStatus.SUCCEEDED, Optional.of(Instant.ofEpochMilli(1000000)), Optional.empty());
            svc.refresh(new FulfillmentQuery("tenant-a", f.order().orderNo()));
            assertEquals("COMPLETED", db.jdbc.queryForObject("SELECT fulfillment_status FROM mall_order", String.class));
            assertEquals(8800, db.jdbc.queryForObject("SELECT cash_paid_minor FROM mall_order", Long.class));
        }
    }

    @Test
    void offlinePrecisionBucketSpanningDueDateKeepsCashButCreatesNoRights() {
        try (var db = new MysqlDatabase()) {
            var helper = new MallFulfillmentMysqlTest();
            var f = helper.setup(db, false);
            freezeOffline(db, 10000250);
            var svc = helper.coordinator(db, f, f.mall().c.uow,
                    Clock.fixed(Instant.ofEpochMilli(10010000), ZoneOffset.UTC));
            f.svc().startPayment(MallOrderConcurrencyMysqlTest.BUYER,
                    new StartPaymentCommand(MallOrderConcurrencyMysqlTest.STORE, f.order().orderNo(), "OFFLINE", "BANK_TRANSFER", null));
            long due = f.mall().c.uow.required(() -> new JdbcMallRepository(db.ds, new JdbcCommerceEventPersistence(db.ds))
                    .order("tenant-a", f.order().orderNo()).orElseThrow().paymentDueAt());
            f.bridge().payment = new PaymentOutcome(f.order().orderNo(), Optional.of("pay"), "platform",
                    f.order().payable(), AsyncStatus.SUCCEEDED, Optional.of(Instant.ofEpochMilli(due / 1000 * 1000)),
                    Optional.empty(), Map.of(), false, Optional.empty(), 1000L);
            svc.refresh(new FulfillmentQuery("tenant-a", f.order().orderNo()));
            assertEquals("PAID", db.jdbc.queryForObject("SELECT pay_status FROM mall_order", String.class));
            assertEquals("REFUNDING", db.jdbc.queryForObject("SELECT refund_status FROM mall_order", String.class));
            assertEquals(0, db.jdbc.queryForObject("SELECT COUNT(*) FROM mall_fulfillment_task WHERE task_type='DIGITAL'", Integer.class));
        }
    }

    @Test
    void cashDiscoveredAfterPayFenceCannotDeliverBeforeCommerceObservesClosure() {
        try (var db = new MysqlDatabase()) {
            var helper = new MallFulfillmentMysqlTest(); var f = helper.setup(db, false);
            freezeOffline(db, 86400000);
            f.svc().startPayment(MallOrderConcurrencyMysqlTest.BUYER,
                    new StartPaymentCommand(MallOrderConcurrencyMysqlTest.STORE,f.order().orderNo(),"OFFLINE","BANK_TRANSFER",null));
            f.bridge().payment = new PaymentOutcome(f.order().orderNo(),Optional.of("pay"),"platform",f.order().payable(),
                    AsyncStatus.SUCCEEDED,Optional.of(Instant.ofEpochMilli(2100)),Optional.empty(),Map.of(),false,Optional.of("OFFLINE_VOID:proof"),1L);
            f.svc().refresh(new FulfillmentQuery("tenant-a",f.order().orderNo()));
            assertEquals("REFUNDING",db.jdbc.queryForObject("SELECT refund_status FROM mall_order",String.class));
            assertEquals(0,db.jdbc.queryForObject("SELECT COUNT(*) FROM mall_fulfillment_task WHERE task_type='DIGITAL'",Integer.class));
        }
    }

    @Test
    void authorizedOfflineOrderCanResumePreparationAfterInitiatorLogsOut() {
        try (var db = new MysqlDatabase()) {
            var helper = new MallFulfillmentMysqlTest(); var f = helper.setup(db,false);
            freezeOffline(db,86400000);
            var outcome=f.svc().prepareOffline(new FulfillmentQuery("tenant-a",f.order().orderNo()));
            assertEquals(AsyncStatus.PENDING,outcome.status());
            assertEquals("p",db.jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(request_snapshot,'$.payment.offline.profileNo')) FROM mall_payment_ref",String.class));
        }
    }

    @Test
    void onTimeActualReceiptConfirmedAfterDueDateStillDeliversWithoutVoidFence() {
        try(var db=new MysqlDatabase()) {
            var helper=new MallFulfillmentMysqlTest();var f=helper.setup(db,false);freezeOffline(db,10000);
            f.svc().prepareOffline(new FulfillmentQuery("tenant-a",f.order().orderNo()));
            var lateReview=helper.coordinator(db,f,f.mall().c.uow,Clock.fixed(Instant.ofEpochMilli(1000000),ZoneOffset.UTC));
            f.bridge().payment=new PaymentOutcome(f.order().orderNo(),Optional.of("pay"),"platform",f.order().payable(),
                    AsyncStatus.SUCCEEDED,Optional.of(Instant.ofEpochMilli(3000)),Optional.empty(),Map.of(),false,Optional.empty(),1000L);
            lateReview.refresh(new FulfillmentQuery("tenant-a",f.order().orderNo()));
            assertEquals("COMPLETED",db.jdbc.queryForObject("SELECT fulfillment_status FROM mall_order",String.class));
            assertEquals(3000,db.jdbc.queryForObject("SELECT paid_at FROM mall_order",Long.class));
        }
    }

    @Test
    void trustedVoidReceiptReleasesOriginalHoldBeforeDeadline() {
        try(var db=new MysqlDatabase()) {
            var helper=new MallFulfillmentMysqlTest();var f=helper.setup(db,false);freezeOffline(db,86400000);
            f.svc().prepareOffline(new FulfillmentQuery("tenant-a",f.order().orderNo()));
            f.bridge().closed=true;
            f.bridge().payment=new PaymentOutcome(f.order().orderNo(),Optional.of("pay"),"platform",f.order().payable(),
                    AsyncStatus.FAILED,Optional.empty(),Optional.empty(),Map.of(),true,Optional.of("OFFLINE_VOID:proof"));
            f.svc().refresh(new FulfillmentQuery("tenant-a",f.order().orderNo()));
            assertEquals("CLOSED",db.jdbc.queryForObject("SELECT pay_status FROM mall_order",String.class));
            assertNull(db.jdbc.queryForObject("SELECT pending_change_order_no FROM mch_subscription_account",String.class));
        }
    }

    void freezeOffline(MysqlDatabase db, long window) {
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        var terms = new OfflineCollectionTerms("p", 1, "platform", "CNY", "test-account", "w1", window, Map.of("bank", "test-only"));
        db.jdbc.update("UPDATE mall_quote SET line_snapshot=JSON_SET(line_snapshot,'$.purchase.offline', CAST(? AS JSON))", json.writeValueAsString(terms));
    }
}
