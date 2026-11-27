package ai.neargo.commerce.persistence;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.commerce.api.common.Money;
import ai.neargo.commerce.api.common.StoreRef;
import ai.neargo.commerce.api.mall.MallException;
import ai.neargo.commerce.api.port.*;
import ai.neargo.commerce.core.mall.RefundSnapshot.*;
import ai.neargo.commerce.core.offline.CashRefundRepository.Refund;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class MallRefundDatabaseMysqlTest {
    @Test void freezesAndResolvesV2TaskUsingItsLockedBusinessSnapshot() {
        try (var db = new MysqlDatabase()) {
            var helper = new MallFulfillmentMysqlTest();
            var f = helper.setup(db, false);
            helper.start(f);
            f.svc().confirmPayment(helper.paid(f, 8800));
            db.jdbc.execute("ALTER TABLE mall_fulfillment_task DROP COLUMN request_hash, DROP COLUMN dispatch_state");
            var refunds = new JdbcRefundRepository(db.ds, f.mall().c.ids,
                    new JdbcSubscriptionRepository(db.ds, f.mall().c.ids), true);
            var orders = new JdbcMallRepository(db.ds, new JdbcCommerceEventPersistence(db.ds));
            f.mall().c.uow.required(() -> {
                var order = orders.order("tenant-a", f.order().orderNo()).orElseThrow();
                var tasks = refunds.pendingTasks(order);
                assertEquals(1, tasks.size());
                var preview = new Preview("ref",order.orderNo(),order.quote().store(),"CNY","buyer",1,2,
                        List.of(),Map.of(),null,false,"digest",null,tasks);
                var state = new State(preview,"APPROVED",8800,0,"ops","ok",null,null);
                refunds.freezeTasks(state);
                assertEquals("FROZEN",db.jdbc.queryForObject(
                        "SELECT status FROM mall_fulfillment_task WHERE task_type='DIGITAL'",String.class));
                refunds.resolveTasks(state,true);
                assertEquals("CANCELLED",db.jdbc.queryForObject(
                        "SELECT status FROM mall_fulfillment_task WHERE task_type='DIGITAL'",String.class));
                return null;
            });
        }
    }

    @Test void sharesOneLateRefundWithIndependentPayoutAndFrozenReview() {
        try (var db = new MysqlDatabase()) {
            new ResourceDatabasePopulator(new ClassPathResource("db/mall/refund/V1__mall_refund.sql"))
                    .execute(db.ds);
            var uow = new JdbcCommerceUnitOfWork(db.ds, new DataSourceTransactionManager(db.ds));
            CommerceIdGenerator ids = namespace -> java.util.UUID.randomUUID().toString();
            var nativeRefunds = new JdbcRefundRepository(db.ds, ids, new JdbcSubscriptionRepository(db.ds, ids), true);
            var payouts = new JdbcMallCashRefundRepository(db.ds);
            var store = new StoreRef("T","S");
            var preview = new Preview("late-original","order",store,"CNY","buyer",10,20,
                    List.of(),Map.of(),null,false,"digest",null);
            var quote = new ReviewQuote("quote","digest",preview);
            var target = new OfflineCashRefundPort.Target(store,"order",true);
            var money = new Money("CNY",75);
            var payment = new PaymentRefundCommand("T","order","late-original",money,"late",store,
                    "attempt","pay","merchant",money);
            var request = new OfflineCashRefundPort.Request("late-original","pay","late");
            var accepted = new Refund(target,request,"ops",12,payment,"PAYOUT_ACCEPTED",null,List.of());
            var approved = new State(preview,"APPROVED",75,0,"ops","proof",payment,null,12L).withReview(quote);
            uow.required(() -> {
                nativeRefunds.create(new State(preview,"REQUESTED",0,0,null,"request",null,null));
                nativeRefunds.createReviewQuote(quote);
                nativeRefunds.save(approved, "approval-refund");
                payouts.save(accepted);
                return null;
            });
            var offlineRequest = new OfflineCashRefundPort.Request("cash-other","pay","late");
            var competing = new Refund(target,offlineRequest,"ops",13,payment,"PREPARED",null,List.of());
            assertThrows(MallException.class, () -> uow.required(() -> { payouts.save(competing); return null; }));
            uow.required(() -> {
                assertEquals(approved,nativeRefunds.find("T","late-original").orElseThrow());
                assertEquals(List.of("late-original"),payouts.pending("T",null,10));
                nativeRefunds.save(approved);
                assertEquals("approval-refund",db.jdbc.queryForObject(
                        "SELECT review_no FROM mall_refund WHERE refund_no='late-original'",String.class));
                assertEquals(accepted,payouts.find("T","late-original",true).orElseThrow());
                payouts.save(accepted.state("REFUNDED",null,List.of()));
                payouts.save(competing);
                assertTrue(nativeRefunds.find("T","cash-other").isEmpty());
                assertEquals(2,payouts.list("T","order").size());
                assertEquals("APPROVED",nativeRefunds.find("T","late-original").orElseThrow().status());
                assertEquals(List.of("cash-other"),payouts.pending("T",null,10));
                return null;
            });
        }
    }
}
