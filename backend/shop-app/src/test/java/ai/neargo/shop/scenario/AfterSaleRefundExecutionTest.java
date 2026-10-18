package ai.neargo.shop.scenario;

import ai.neargo.shop.trade.service.AfterSaleService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.trade.order.refund.service.RefundExecutionService;
import ai.neargo.shop.pay.channel.TestPayGateway;
import ai.neargo.shop.pay.channel.PayGateway.QueryResult;
import static org.mockito.Mockito.doReturn;
import ai.neargo.shop.pay.SettleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ai.neargo.shop.spi.points.*;
import ai.neargo.shop.spi.settle.SettlePort.PaymentStore;
import ai.neargo.shop.spi.user.MerchantQueryPort.PaymentRoute;
import ai.neargo.shop.trade.order.points.*;
import java.time.Instant;
import java.util.List;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

/** Exercises the real merchant entry and durable recovery, with synthetic store subjects and a test bank. */
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:refund-entry;"
        + "MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class AfterSaleRefundExecutionTest {
    @Autowired private AfterSaleService afterSales;
    @Autowired private RefundExecutionService executions;
    @Autowired private OrderPointsRecoveryService pointsRecovery;
    @Autowired private OrderPointsRefundCoordinator pointsRefundSource;
    @Autowired private SettleService settle;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private TestPayGateway channel;
    @MockitoBean private PointsPaymentSnapshotPort pointsPayments;
    @MockitoBean private PointsRefundPort pointsRefunds;
    @MockitoBean private OrderPointsBindingService pointsBindings;
    @MockitoBean private OrderPointsOutboxConsumer pointsDelivery;
    @MockitoBean private OrderPointsClosureFlowService pointsClosure;

    @Test void integrated_refund_waits_for_verified_net_cash_and_points_confirmation() {
        var f=fixture();
        jdbc.update("UPDATE ord_order SET points_integrated=1,currency='CNY' WHERE order_no=?",f.sub());
        var route=new PaymentRoute("MERCHANT","STORE","PM-TEST","TEST","SM","PM:1","DIRECT");
        var proof=new PointsPaymentSnapshotPort.VerifiedPayment(f.sub(),"USER","CNY",110,"TEST",
                "OUT:"+f.sub(),"TRADE",Instant.ofEpochMilli(1000),List.of(new PaymentStore(f.sub(),route)));
        doReturn(java.util.Optional.of(proof)).when(pointsPayments).verified(f.sub());
        var payment=new PointsRefundPort.PaymentSnapshot("DIRECT","TEST","MERCHANT","STORE",
                "PM:1","SM","TRADE","OUT:"+f.sub(),110);
        var snapshot=new OrderPointsBindingService.Snapshot(100,0,0,1,1,"{}","{}",payment,
                List.of(new PointsRedeemPort.Item(String.valueOf(f.item()),100)));
        var admission=new OrderPointsBindingService.Admission("REQ",f.sub(),f.sub(),"USER","MERCHANT",
                "STORE","TYPE","CNY","ONLINE",0,true,true,snapshot);
        doReturn(new OrderPointsBindingService.Binding("B",admission,
                OrderPointsBindingService.Phase.BOUND,0,"H")).when(pointsBindings).find(f.sub());
        var prep=new PointsRefundPort.RefundPreparation("PREP:"+f.as(),"TYPE",1,"HASH",0,0,0,0,
                10,50,0,null,"PREPARED");
        doReturn(prep).when(pointsRefunds).prepare(any());
        doReturn(new PointsRefundPort.CashDecision(prep.preparationNo(),prep.inputHash(),1,f.as(),
                f.sub(),"USER","CNY","TEST",50,10,null,payment))
                .when(pointsRefunds).authorizeCash(prep.preparationNo(),prep.inputHash(),1);
        doReturn(new PointsRefundPort.RefundOutcome(prep.preparationNo(),"CONFIRMED",0,0,0,0,0,
                null,false)).when(pointsRefunds).confirm(any());
        afterSales.approve("MERCHANT",f.as(),"approved",null,"CASH");
        afterSales.resumeRefund(f.as(),"SYSTEM");
        doReturn(new PointsRefundPort.RefundRecoveryStatus(prep.preparationNo(), f.as(), f.sub(),
                "CONFIRMED", 50, jdbc.queryForObject(
                        "SELECT payment_no FROM stl_payment WHERE after_sale_no=?", String.class, f.as()), false))
                .when(pointsRefunds).recoveryStatus(prep.preparationNo(), prep.inputHash());
        assertThat(refunded(f.sub())).isZero();
        assertThat(jdbc.queryForObject("SELECT amount_minor FROM stl_payment WHERE after_sale_no=?",
                Long.class,f.as())).isEqualTo(50);
        afterSales.resumeRefund(f.as(),"SYSTEM");
        assertThat(status(f.as())).isEqualTo("REFUNDED");
        assertThat(jdbc.queryForObject("SELECT status FROM stl_points_refund_hold WHERE after_sale_no=?",
                String.class, f.as())).isEqualTo("CONFIRMED");
        assertThat(refunded(f.sub())).isEqualTo(60);
        verify(pointsRefunds,times(1)).confirm(any());
        verify(pointsClosure).finalizeSubOrder(f.sub());
    }

    @Test void integrated_zero_cash_refund_uses_source_completion_fact_without_payment() {
        var f=fixture();
        jdbc.update("UPDATE ord_order SET points_integrated=1,currency='CNY' WHERE order_no=?",f.sub());
        var route=new PaymentRoute("MERCHANT","STORE","PM-TEST","TEST","SM","PM:1","DIRECT");
        var proof=new PointsPaymentSnapshotPort.VerifiedPayment(f.sub(),"USER","CNY",110,"TEST",
                "OUT:"+f.sub(),"TRADE",Instant.ofEpochMilli(1000),List.of(new PaymentStore(f.sub(),route)));
        doReturn(java.util.Optional.of(proof)).when(pointsPayments).verified(f.sub());
        var payment=new PointsRefundPort.PaymentSnapshot("DIRECT","TEST","MERCHANT","STORE",
                "PM:1","SM","TRADE","OUT:"+f.sub(),110);
        var snapshot=new OrderPointsBindingService.Snapshot(100,0,0,1,1,"{}","{}",payment,
                List.of(new PointsRedeemPort.Item(String.valueOf(f.item()),100)));
        var admission=new OrderPointsBindingService.Admission("REQ",f.sub(),f.sub(),"USER","MERCHANT",
                "STORE","TYPE","CNY","ONLINE",0,true,true,snapshot);
        doReturn(new OrderPointsBindingService.Binding("B",admission,
                OrderPointsBindingService.Phase.BOUND,0,"H")).when(pointsBindings).find(f.sub());
        var prep=new PointsRefundPort.RefundPreparation("ZERO:"+f.as(),"TYPE",1,"HASH",0,0,0,0,
                60,0,0,"COLLECT:"+f.as(),"COLLECTED");
        doReturn(prep).when(pointsRefunds).prepare(any());
        doReturn(new PointsRefundPort.RefundOutcome(prep.preparationNo(),"CONFIRMED",0,0,60,0,0,
                null,false)).when(pointsRefunds).confirm(any());
        doReturn(new PointsRefundPort.RefundRecoveryStatus(prep.preparationNo(), f.as(), f.sub(),
                "CONFIRMED", 0, null, false))
                .when(pointsRefunds).recoveryStatus(prep.preparationNo(), prep.inputHash());
        afterSales.approve("MERCHANT",f.as(),"approved",null,"CASH");
        afterSales.resumeRefund(f.as(),"SYSTEM");
        assertThat(status(f.as())).isEqualTo("REFUNDED");
        assertThat(jdbc.queryForObject("SELECT status FROM stl_points_refund_hold WHERE after_sale_no=?",
                String.class, f.as())).isEqualTo("CONFIRMED");
        assertThat(refunded(f.sub())).isEqualTo(60);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stl_payment WHERE after_sale_no=?",
                Long.class,f.as())).isZero();
        verify(pointsDelivery).deliver("ORDER:ZERO_CASH:"+f.as(),f.sub());
        verify(channel,never()).refund(any(),anyLong(),anyString(),anyString());
    }

    @Test void failed_source_delivery_retries_original_cash_refund_without_repaying() {
        var f = fixture();
        jdbc.update("UPDATE ord_order SET points_integrated=1,currency='CNY' WHERE order_no=?", f.sub());
        var route = new PaymentRoute("MERCHANT", "STORE", "PM-TEST", "TEST", "SM", "PM:1", "DIRECT");
        var proof = new PointsPaymentSnapshotPort.VerifiedPayment(f.sub(), "USER", "CNY", 110, "TEST",
                "OUT:" + f.sub(), "TRADE", Instant.ofEpochMilli(1000), List.of(new PaymentStore(f.sub(), route)));
        doReturn(java.util.Optional.of(proof)).when(pointsPayments).verified(f.sub());
        var payment = new PointsRefundPort.PaymentSnapshot("DIRECT", "TEST", "MERCHANT", "STORE",
                "PM:1", "SM", "TRADE", "OUT:" + f.sub(), 110);
        var snapshot = new OrderPointsBindingService.Snapshot(100, 0, 0, 1, 1, "{}", "{}", payment,
                List.of(new PointsRedeemPort.Item(String.valueOf(f.item()), 100)));
        var admission = new OrderPointsBindingService.Admission("REQ", f.sub(), f.sub(), "USER", "MERCHANT",
                "STORE", "TYPE", "CNY", "ONLINE", 0, true, true, snapshot);
        doReturn(new OrderPointsBindingService.Binding("B", admission,
                OrderPointsBindingService.Phase.BOUND, 0, "H")).when(pointsBindings).find(f.sub());
        var prep = new PointsRefundPort.RefundPreparation("PREP:" + f.as(), "TYPE", 1, "HASH",
                0, 0, 0, 0, 10, 50, 0, null, "PREPARED");
        doReturn(prep).when(pointsRefunds).prepare(any());
        doReturn(new PointsRefundPort.CashDecision(prep.preparationNo(), prep.inputHash(), 1,
                f.as(), f.sub(), "USER", "CNY", "TEST", 50, 10, null, payment))
                .when(pointsRefunds).authorizeCash(prep.preparationNo(), prep.inputHash(), 1);
        doReturn(new PointsRefundPort.RefundOutcome(prep.preparationNo(), "CONFIRMED", 0, 0, 0, 0, 0,
                null, false)).when(pointsRefunds).confirm(any());
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call -> {
            if (attempts.getAndIncrement() == 0) throw new IllegalStateException("POINTS_EVENT_DELIVERY_FAILED");
            return null;
        }).when(pointsDelivery).deliver("ORDER:REFUND_CONFIRM:" + f.as(), f.sub());
        afterSales.approve("MERCHANT", f.as(), "approved", null, "CASH");
        afterSales.resumeRefund(f.as(), "SYSTEM");
        doReturn(new PointsRefundPort.RefundRecoveryStatus(prep.preparationNo(), f.as(), f.sub(),
                "CONFIRMED", 50, jdbc.queryForObject(
                        "SELECT payment_no FROM stl_payment WHERE after_sale_no=?", String.class, f.as()), false))
                .when(pointsRefunds).recoveryStatus(prep.preparationNo(), prep.inputHash());
        assertThatThrownBy(() -> afterSales.resumeRefund(f.as(), "SYSTEM"))
                .hasMessage("POINTS_EVENT_DELIVERY_FAILED");
        assertThat(status(f.as())).isEqualTo("REFUNDING");
        assertThat(refunded(f.sub())).isZero();
        afterSales.resumeRefund(f.as(), "SYSTEM");
        assertThat(status(f.as())).isEqualTo("REFUNDED");
        assertThat(refunded(f.sub())).isEqualTo(60);
        assertThat(attempts.get()).isEqualTo(2);
        verify(channel, times(1)).refund(any(), anyLong(), anyString(), anyString());
    }

    @Test void definitive_cash_failure_after_collection_requests_original_compensation() {
        var f=compensatedFixture();
        assertThat(status(f.as())).isEqualTo("REFUNDING");
        assertThat(refunded(f.sub())).isZero();
        assertThat(jdbc.queryForObject("SELECT phase FROM ord_points_refund_binding WHERE after_sale_no=?",
                String.class, f.as())).isEqualTo("COMPENSATED_PENDING_OPERATOR");
        assertThat(jdbc.queryForObject("SELECT status FROM stl_points_refund_hold WHERE after_sale_no=?",
                String.class, f.as())).isEqualTo("CANCELLED");
        assertThat(afterSales.stuckRefundNos(System.currentTimeMillis() + 1000, 20))
                .doesNotContain(f.as());
        assertThat(pointsRefundSource.pending(20)).doesNotContain(f.as());
        assertThat(pointsRefundSource.manualForStore("STORE", "", 20).rows())
                .extracting(ai.neargo.shop.trade.order.points.OrderPointsRefundCoordinator.ManualRefund::afterSaleNo)
                .contains(f.as());
        assertThat(pointsRecovery.inspect(f.sub()).differences())
                .contains("REFUND_OPERATOR_ACTION_REQUIRED");
        afterSales.resumeRefund(f.as(), "SYSTEM");
        verify(pointsRefunds,times(1)).requestCompensation(any(),eq(1L));
        verify(channel,times(1)).refund(any(),anyLong(),anyString(),anyString());
        assertThatThrownBy(() -> afterSales.closeCompensatedRefund(f.as(), "OPS-1", " "))
                .hasMessageContaining("BAD_REQUEST");
        afterSales.closeCompensatedRefund(f.as(), "OPS-1", "补收已回退，现金退款失败，关闭原售后");
        afterSales.closeCompensatedRefund(f.as(), "OPS-1", "补收已回退，现金退款失败，关闭原售后");
        assertThat(status(f.as())).isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject("SELECT state FROM ord_refund_execution WHERE after_sale_no=?",
                String.class, f.as())).isEqualTo("ABORTED");
        assertThat(jdbc.queryForObject("SELECT phase FROM ord_points_refund_binding WHERE after_sale_no=?",
                String.class, f.as())).isEqualTo("ABORTED");
        assertThat(pointsRefundSource.manualForStore("STORE", "", 20).rows())
                .extracting(ai.neargo.shop.trade.order.points.OrderPointsRefundCoordinator.ManualRefund::afterSaleNo)
                .doesNotContain(f.as());
        assertThat(refunded(f.sub())).isZero();
        assertThatThrownBy(() -> afterSales.retryCompensatedRefund(f.as(), "OPS-1", "重新发起原退款"))
                .hasMessageContaining("ORDER_STATE_ILLEGAL");
    }

    @Test void operator_retry_closes_original_and_creates_one_new_attempt_atomically() {
        var f=compensatedFixture();
        var retry = afterSales.retryCompensatedRefund(f.as(), "OPS-1", "重新发起原退款");
        assertThat(status(f.as())).isEqualTo("CLOSED");
        assertThat(retry.afterSaleNo()).isNotEqualTo(f.as());
        assertThat(retry.status()).isEqualTo("REFUNDING");
        assertThat(executions.inspect(retry.afterSaleNo()).plan())
                .isEqualTo(executions.inspect(f.as()).plan());
        assertThat(afterSales.retryCompensatedRefund(f.as(), "OPS-1", "重新发起原退款")
                .afterSaleNo()).isEqualTo(retry.afterSaleNo());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ord_points_refund_retry WHERE old_after_sale_no=?",
                Long.class, f.as())).isEqualTo(1);
    }

    @Test void cash_failure_without_collection_reaches_operator_queue_after_pay_abort() {
        var f = cashFailureFixture(false);
        assertThat(status(f.as())).isEqualTo("REFUNDING");
        assertThat(jdbc.queryForObject("SELECT phase FROM ord_points_refund_binding WHERE after_sale_no=?",
                String.class, f.as())).isEqualTo("CASH_FAILED_PENDING_OPERATOR");
        assertThat(pointsRefundSource.manualForStore("STORE", "", 20).rows())
                .extracting(ai.neargo.shop.trade.order.points.OrderPointsRefundCoordinator.ManualRefund::afterSaleNo)
                .contains(f.as());
        assertThat(pointsRecovery.inspect(f.sub()).differences())
                .contains("REFUND_OPERATOR_ACTION_REQUIRED");
        assertThat(afterSales.stuckRefundNos(System.currentTimeMillis() + 1000, 20))
                .doesNotContain(f.as());
        assertThat(pointsRefundSource.pending(20)).doesNotContain(f.as());
        afterSales.closeCompensatedRefund(f.as(), "OPS-1", "现金退款失败且没有补收，关闭原售后");
        assertThat(status(f.as())).isEqualTo("CLOSED");
        assertThat(refunded(f.sub())).isZero();
    }

    @Test void cash_failure_without_collection_can_retry_with_a_new_frozen_attempt() {
        var f = cashFailureFixture(false);
        var retried = afterSales.retryCompensatedRefund(f.as(), "OPS-1", "重新发起未补收退款");
        assertThat(status(f.as())).isEqualTo("CLOSED");
        assertThat(retried.status()).isEqualTo("REFUNDING");
        assertThat(retried.afterSaleNo()).isNotEqualTo(f.as());
        assertThat(executions.inspect(retried.afterSaleNo()).plan())
                .isEqualTo(executions.inspect(f.as()).plan());
    }

    private Fixture compensatedFixture() {
        return cashFailureFixture(true);
    }

    private Fixture cashFailureFixture(boolean collected) {
        var f=fixture();
        jdbc.update("UPDATE ord_order SET points_integrated=1,currency='CNY' WHERE order_no=?",f.sub());
        var route=new PaymentRoute("MERCHANT","STORE","PM-TEST","TEST","SM","PM:1","DIRECT");
        var proof=new PointsPaymentSnapshotPort.VerifiedPayment(f.sub(),"USER","CNY",110,"TEST",
                "OUT:"+f.sub(),"TRADE",Instant.ofEpochMilli(1000),List.of(new PaymentStore(f.sub(),route)));
        doReturn(java.util.Optional.of(proof)).when(pointsPayments).verified(f.sub());
        var payment=new PointsRefundPort.PaymentSnapshot("DIRECT","TEST","MERCHANT","STORE",
                "PM:1","SM","TRADE","OUT:"+f.sub(),110);
        var snapshot=new OrderPointsBindingService.Snapshot(100,0,0,1,1,"{}","{}",payment,
                List.of(new PointsRedeemPort.Item(String.valueOf(f.item()),100)));
        var admission=new OrderPointsBindingService.Admission("REQ",f.sub(),f.sub(),"USER","MERCHANT",
                "STORE","TYPE","CNY","ONLINE",0,true,true,snapshot);
        doReturn(new OrderPointsBindingService.Binding("B",admission,
                OrderPointsBindingService.Phase.BOUND,0,"H")).when(pointsBindings).find(f.sub());
        var prep=new PointsRefundPort.RefundPreparation("PREP:"+f.as(),"TYPE",1,"HASH",0,0,0,0,
                10,50,0,collected ? "COLLECT:"+f.as() : null,collected ? "COLLECTED" : "PREPARED");
        doReturn(prep).when(pointsRefunds).prepare(any());
        doReturn(new PointsRefundPort.CashDecision(prep.preparationNo(),prep.inputHash(),1,f.as(),
                f.sub(),"USER","CNY","TEST",50,10,prep.collectionTicket(),payment))
                .when(pointsRefunds).authorizeCash(prep.preparationNo(),prep.inputHash(),1);
        doReturn(ai.neargo.shop.pay.channel.PayGateway.Result.fatal("rejected"))
                .when(channel).refund(any(),anyLong(),anyString(),anyString());
        afterSales.approve("MERCHANT",f.as(),"approved",null,"CASH");
        afterSales.resumeRefund(f.as(),"SYSTEM");
        if (!collected) doReturn(new PointsRefundPort.RefundRecoveryStatus(prep.preparationNo(), f.as(), f.sub(),
                "ABORTED", 50, jdbc.queryForObject(
                        "SELECT payment_no FROM stl_payment WHERE after_sale_no=?", String.class, f.as()), false, false))
                .when(pointsRefunds).recoveryStatus(prep.preparationNo(), prep.inputHash());
        afterSales.resumeRefund(f.as(),"SYSTEM");
        assertThat(status(f.as())).isEqualTo("REFUNDING");
        assertThat(refunded(f.sub())).isZero();
        assertThat(jdbc.queryForObject("SELECT phase FROM ord_points_refund_binding WHERE after_sale_no=?",
                String.class,f.as())).isEqualTo(collected ? "COMPENSATING" : "CASH_FAILED_PENDING_OPERATOR");
        if (collected) verify(pointsRefunds).requestCompensation(any(),eq(1L));
        else verify(pointsRefunds, never()).requestCompensation(any(), anyLong());
        verify(pointsRefunds,never()).confirm(any());
        if (!collected) {
            verify(pointsRefunds).abort(any());
            return f;
        }
        assertThatThrownBy(() -> afterSales.closeCompensatedRefund(f.as(), "OPS-1", "too early"))
                .hasMessageContaining("ORDER_STATE_ILLEGAL");
        doReturn(new PointsRefundPort.RefundRecoveryStatus(prep.preparationNo(), f.as(), f.sub(),
                "ABORTED", 50, jdbc.queryForObject(
                        "SELECT payment_no FROM stl_payment WHERE after_sale_no=?", String.class, f.as()), collected, collected))
                .when(pointsRefunds).recoveryStatus(prep.preparationNo(), prep.inputHash());
        afterSales.resumeRefund(f.as(),"SYSTEM");
        return f;
    }

    @Test void approval_reserves_then_worker_sends_outside_transactions_and_verified_query_finishes_once() {
        var f=fixture();
        afterSales.approve("MERCHANT",f.as(),"approved",null,"CASH");
        assertThat(status(f.as())).isEqualTo("REFUNDING");
        assertThat(refunded(f.sub())).isZero();
        verify(channel,never()).refund(any(),anyLong(),anyString(),anyString());
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return call.callRealMethod();
        }).when(channel).refund(any(),anyLong(),anyString(),anyString());
        afterSales.resumeRefund(f.as(),"SYSTEM");
        assertThat(status(f.as())).isEqualTo("REFUNDING");
        assertThat(refunded(f.sub())).isZero();
        afterSales.resumeRefund(f.as(),"SYSTEM");
        afterSales.resumeRefund(f.as(),"SYSTEM");
        assertThat(status(f.as())).isEqualTo("REFUNDED");
        assertThat(refunded(f.sub())).isEqualTo(60);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ord_status_log WHERE aggregate_no=?"
                + " AND status='REFUNDED'",Long.class,f.as())).isEqualTo(1);
        verify(channel,times(1)).refund(any(),anyLong(),anyString(),anyString());
    }

    @Test void points_decision_is_required_before_any_cash_is_opened() {
        var f=fixture();
        jdbc.update("UPDATE ord_sub_order SET points_deduct=10 WHERE sub_order_no=?",f.sub());
        afterSales.approve("MERCHANT",f.as(),null,null,"CASH");
        assertThatThrownBy(() -> afterSales.resumeRefund(f.as(),"SYSTEM"))
                .hasMessage("REFUND_EXECUTION_POINTS_CONFIRMATION_REQUIRED");
        assertThat(refunded(f.sub())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stl_payment WHERE after_sale_no=?",
                Long.class,f.as())).isZero();
        verify(channel,never()).refund(any(),anyLong(),anyString(),anyString());
    }

    @Test void recovery_reuses_cash_created_before_binding_and_never_duplicates_the_intent() {
        var f=fixture();
        afterSales.approve("MERCHANT",f.as(),null,null,"CASH");
        executions.beginCash(f.as());
        String no=settle.prepareRefund(f.sub(),f.as(),60,"recovery");
        afterSales.resumeRefund(f.as(),"SYSTEM");
        afterSales.resumeRefund(f.as(),"SYSTEM");
        assertThat(status(f.as())).isEqualTo("REFUNDED");
        assertThat(jdbc.queryForObject("SELECT refund_payment_no FROM ord_after_sale WHERE after_sale_no=?",
                String.class,f.as())).isEqualTo(no);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stl_payment WHERE after_sale_no=?",
                Long.class,f.as())).isEqualTo(1);
    }

    @Test void already_split_orders_wait_for_durable_reversal_before_any_cash_refund() {
        var f=fixture();
        jdbc.update("UPDATE stl_bill SET status='SPLIT_CONFIRMED',split_at=1 WHERE sub_order_no=?",f.sub());
        afterSales.approve("MERCHANT",f.as(),null,null,"CASH");
        assertThatThrownBy(() -> afterSales.resumeRefund(f.as(),"SYSTEM")).isInstanceOf(RuntimeException.class);
        assertThat(status(f.as())).isEqualTo("REFUNDING");
        verify(channel,never()).refund(any(),anyLong(),anyString(),anyString());
    }

    @Test void concurrent_recovery_finishes_one_business_refund_once() throws Exception {
        var f=fixture();
        afterSales.approve("MERCHANT",f.as(),null,null,"CASH");
        afterSales.resumeRefund(f.as(),"SYSTEM");
        var start=new CountDownLatch(1);
        try (var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(() -> { start.await(); afterSales.resumeRefund(f.as(),"SYSTEM"); return true; });
            var second=pool.submit(() -> { start.await(); afterSales.resumeRefund(f.as(),"SYSTEM"); return true; });
            start.countDown();
            assertThat(first.get(10,TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(10,TimeUnit.SECONDS)).isTrue();
        }
        assertThat(refunded(f.sub())).isEqualTo(60);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ord_status_log WHERE aggregate_no=?"
                + " AND status='REFUNDED'",Long.class,f.as())).isEqualTo(1);
        verify(channel,times(1)).refund(any(),anyLong(),anyString(),anyString());
    }

    @Test void fresh_preparations_are_scheduled_and_blocked_work_does_not_starve_other_rows() {
        var first=fixture(); var second=fixture();
        afterSales.approve("MERCHANT",first.as(),null,null,"CASH");
        afterSales.approve("MERCHANT",second.as(),null,null,"CASH");
        var a=afterSales.stuckRefundNos(0,1);
        var b=afterSales.stuckRefundNos(0,1);
        assertThat(a).hasSize(1);
        assertThat(b).hasSize(1).isNotEqualTo(a);
    }

    @Test void old_points_compensation_mode_is_rejected_without_opening_money() {
        var f=fixture();
        assertThatThrownBy(() -> afterSales.approve("MERCHANT",f.as(),null,null,"POINTS"))
                .isInstanceOf(BizException.class);
        assertThat(status(f.as())).isEqualTo("APPLIED");
        verify(channel,never()).refund(any(),anyLong(),anyString(),anyString());
    }

    @Test void approved_amount_cannot_change_after_preparation_or_completion() {
        var f=fixture();
        jdbc.update("UPDATE ord_sub_order SET status='COMPLETED' WHERE sub_order_no=?",f.sub());
        afterSales.approve("MERCHANT",f.as(),null,60L,"CASH");
        assertThatThrownBy(() -> afterSales.approve("MERCHANT",f.as(),null,30L,"CASH"))
                .isInstanceOf(BizException.class);
        afterSales.resumeRefund(f.as(),"SYSTEM");
        afterSales.resumeRefund(f.as(),"SYSTEM");
        assertThatThrownBy(() -> afterSales.approve("MERCHANT",f.as(),null,40L,"CASH"))
                .isInstanceOf(BizException.class);
        assertThat(jdbc.queryForObject("SELECT approved_minor FROM ord_after_sale WHERE after_sale_no=?",
                Long.class,f.as())).isEqualTo(60);
        assertThat(refunded(f.sub())).isEqualTo(60);
    }

    @Test void query_transport_unknown_keeps_source_frozen_and_does_not_resubmit() {
        var f=fixture();
        afterSales.approve("MERCHANT",f.as(),null,null,"CASH");
        afterSales.resumeRefund(f.as(),"SYSTEM");
        doAnswer(call -> { throw new IllegalStateException("transport lost"); }).when(channel).queryRefund(anyString());
        afterSales.resumeRefund(f.as(),"SYSTEM");
        afterSales.resumeRefund(f.as(),"SYSTEM");
        assertThat(status(f.as())).isEqualTo("REFUNDING");
        assertThat(refunded(f.sub())).isZero();
        verify(channel,times(1)).refund(any(),anyLong(),anyString(),anyString());
    }

    @Test void wrong_query_amount_cannot_confirm_cash_or_business_refund() {
        var f=fixture();
        afterSales.approve("MERCHANT",f.as(),null,null,"CASH");
        afterSales.resumeRefund(f.as(),"SYSTEM");
        doReturn(new QueryResult(true,true,true,59,"WRONG:"+f.as())).when(channel).queryRefund(anyString());
        assertThatThrownBy(() -> afterSales.resumeRefund(f.as(),"SYSTEM"))
                .hasMessage("PAY_REFUND_VERIFIED_AMOUNT_MISMATCH");
        assertThat(status(f.as())).isEqualTo("REFUNDING");
        assertThat(refunded(f.sub())).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM stl_payment WHERE after_sale_no=?",
                String.class,f.as())).isEqualTo("PENDING");
    }

    private String status(String as) {
        return jdbc.queryForObject("SELECT status FROM ord_after_sale WHERE after_sale_no=?",String.class,as);
    }
    private Fixture fixture() {
        String sub="S"+UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO ord_sub_order(sub_order_no,order_no,user_no,entity_no,store_no,status,fulfillment,"
                + "pay_amount,goods_amount,freight_amount,created_at,updated_at)"
                + " VALUES(?,?,'USER','MERCHANT','STORE','FULFILLING','STORE_PICKUP',110,100,10,NOW(),NOW())",sub,sub);
        jdbc.update("INSERT INTO ord_item(sub_order_no,order_no,goods_no,sku_no,title,price,qty,amount,"
                + "paid_share_minor,created_at,updated_at) VALUES(?,?,'G','SKU','ITEM',50,2,100,100,NOW(),NOW())",sub,sub);
        long item=jdbc.queryForObject("SELECT id FROM ord_item WHERE sub_order_no=?",Long.class,sub);
        jdbc.update("INSERT INTO stl_payment(payment_no,direction,order_no,user_no,amount_minor,currency,status,"
                + "pay_channel,created_at,updated_at) VALUES(?,'PAY',?,'USER',110,'CNY','SUCCESS','TEST',NOW(),NOW())",
                "ORIG:"+sub,sub);
        String as=afterSale(sub,60);
        jdbc.update("INSERT INTO ord_order(order_no,user_no,pay_amount,status,pay_channel,created_at,updated_at)"
                + " VALUES(?,'USER',110,'PAID','TEST',NOW(),NOW())",sub);
        jdbc.update("INSERT INTO ord_after_sale_item(after_sale_no,sub_order_no,item_id,qty,refund_minor,"
                + "created_at,updated_at) VALUES(?,?,?,1,50,NOW(),NOW())",as,sub,item);
        String trade=channel.placeOrder("OUT:"+sub,110);
        channel.markPaid("OUT:"+sub);
        jdbc.update("UPDATE stl_payment SET out_trade_no=?,trade_no=? WHERE payment_no=?",
                "OUT:"+sub,trade,"ORIG:"+sub);
        jdbc.update("INSERT INTO stl_bill(settle_no,sub_order_no,order_no,entity_no,store_no,pay_merchant_no,"
                + "gross_minor,commission_minor,service_fee_minor,net_minor,status,funds_mode,created_at,updated_at)"
                + " VALUES(?,?,?,'MERCHANT','STORE','PM-TEST',110,0,0,110,'PENDING','DIRECT',NOW(),NOW())",sub,sub,sub);
        return new Fixture(sub,as,item);
    }
    private String afterSale(String sub,long amount) {
        String as="A"+UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO ord_after_sale(after_sale_no,sub_order_no,order_no,user_no,entity_no,type,status,"
                + "refund_minor,approved_minor,reason,created_at,updated_at)"
                + " VALUES(?,?,?,'USER','MERCHANT','REFUND_ONLY','APPLIED',?,?,'OTHER',NOW(),NOW())",as,sub,sub,amount,amount);
        return as;
    }
    private void cash(String no,Fixture f,long amount,String status) {
        jdbc.update("INSERT INTO stl_payment(payment_no,direction,order_no,sub_order_no,after_sale_no,user_no,"
                + "amount_minor,currency,status,pay_channel,created_at,updated_at)"
                + " VALUES(?,'REFUND',?,?,?,'USER',?,'CNY',?,'TEST',NOW(),NOW())",no,f.sub(),f.sub(),f.as(),amount,status);
    }
    private long refunded(String sub) {
        return jdbc.queryForObject("SELECT refunded_minor FROM ord_sub_order WHERE sub_order_no=?",Long.class,sub);
    }
    private record Fixture(String sub,String as,long item) { }
}
