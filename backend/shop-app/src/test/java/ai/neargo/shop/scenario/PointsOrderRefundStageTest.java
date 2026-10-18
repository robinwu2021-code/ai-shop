package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ai.neargo.shop.spi.points.*;
import ai.neargo.shop.spi.settle.SettlePort.PaymentStore;
import ai.neargo.shop.spi.user.MerchantQueryPort.PaymentRoute;
import ai.neargo.shop.trade.order.points.*;
import ai.neargo.shop.trade.order.refund.model.*;
import ai.neargo.shop.trade.order.refund.service.RefundExecutionService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@SpringBootTest(
        properties =
                "spring.datasource.url=jdbc:h2:mem:points-refund-stage;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class PointsOrderRefundStageTest {
    @Autowired OrderPointsRefundCoordinator refunds;
    @Autowired ai.neargo.shop.trade.service.OrderService orders;
    @Autowired ai.neargo.shop.trade.order.points.OrderPointsFactService journal;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager sourceTx;
    @MockitoBean OrderPointsBindingService bindings;
    @MockitoBean RefundExecutionService executions;

    @Test
    void partial_refund_freezes_only_the_affected_lines_cumulative_original_goods() {
        var f = fixture();
        var first =
                new TransactionTemplate(sourceTx)
                        .execute(s -> refunds.stage(f.as, f.plan, f.proof));
        assertThat(first.command().businessCashMinor()).isEqualTo(45);
        assertThat(first.command().cumulativeGoodsMinor()).isEqualTo(50);
        assertThat(first.command().lines())
                .extracting(PointsRefundPort.RefundLine::cumulativeGoodsMinor)
                .containsExactly(50L, 0L);
        var replay =
                new TransactionTemplate(sourceTx)
                        .execute(s -> refunds.stage(f.as, f.plan, f.proof));
        assertThat(replay).isEqualTo(first);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ord_points_refund_binding WHERE"
                                    + " after_sale_no=?",
                                Long.class,
                                f.as))
                .isEqualTo(1);
    }

    @Test
    void changed_paid_route_cannot_create_a_refund_binding() {
        var f = fixture();
        var route = new PaymentRoute("M", "ST", "PM", "TEST", "changed", "PM:1", "DIRECT");
        var wrong =
                new PointsPaymentSnapshotPort.VerifiedPayment(
                        f.proof.orderNo(),
                        "U",
                        "CNY",
                        270,
                        "TEST",
                        f.proof.outTradeNo(),
                        f.proof.tradeNo(),
                        Instant.ofEpochMilli(1000),
                        List.of(new PaymentStore(f.sub, route)));
        assertThatThrownBy(
                        () ->
                                new TransactionTemplate(sourceTx)
                                        .execute(s -> refunds.stage(f.as, f.plan, wrong)))
                .hasMessageContaining("POINTS_REFUND_PAYMENT_MISMATCH");
        assertThat(refunds.inspect(f.as)).isNull();
    }

    @Test
    void second_open_refund_includes_reserved_cash_in_cumulative_goods() {
        var f = fixture();
        new TransactionTemplate(sourceTx).execute(s -> refunds.stage(f.as, f.plan, f.proof));
        String next = "A" + UUID.randomUUID().toString().replace("-", "");
        long item = f.plan.lines().getFirst().itemId();
        var second = new RefundPlan(f.sub, List.of(new RefundLine(item, 0, 25)), 0);
        jdbc.update(
                "INSERT INTO ord_after_sale(after_sale_no,sub_order_no,order_no,user_no,entity_no,type,status,"
                        + "refund_mode,refund_minor,approved_minor,reason,created_at,updated_at)"
                        + " VALUES(?,?,(SELECT order_no FROM ord_sub_order WHERE sub_order_no=?),"
                        + "'U','M','REFUND_ONLY','REFUNDING','CASH',25,25,'OTHER',NOW(),NOW())",
                next, f.sub, f.sub);
        when(executions.inspect(next)).thenReturn(new RefundExecutionService.Execution(
                new RefundExecutionService.Preparation(next, f.sub, "H2", "PREPARED", 25),
                second, null));

        var staged = new TransactionTemplate(sourceTx)
                .execute(s -> refunds.stage(next, second, f.proof));
        assertThat(staged.command().lines())
                .filteredOn(line -> line.itemId().equals(String.valueOf(item)))
                .extracting(PointsRefundPort.RefundLine::cumulativeGoodsMinor)
                .containsExactly(77L);
    }

    @Test void only_matching_pay_preparation_and_authorized_revision_open_cash() {
        var f=fixture();
        var stage=new TransactionTemplate(sourceTx).execute(s -> refunds.stage(f.as,f.plan,f.proof));
        var prep=new PointsRefundPort.RefundPreparation("PREP:"+f.as,"TYPE",1,"HASH",7,3,0,
                0,0,45,0,null,"PREPARED");
        assertThatThrownBy(() -> refunds.acceptPreparation(f.as,new PointsRefundPort.RefundPreparation(
                "PREP:"+f.as,"TYPE",1,"HASH",0,0,0,0,0,46,0,null,"PREPARED")))
                .hasMessageContaining("POINTS_REFUND_PREPARATION_MISMATCH");
        var accepted=refunds.acceptPreparation(f.as,prep);
        assertThat(accepted.phase()).isEqualTo(OrderPointsRefundCoordinator.Phase.PREPARED);
        assertThat(refunds.confirmedTotals(f.sub)).isEqualTo(
                new OrderPointsRefundCoordinator.ConfirmedTotals(0,0));
        assertThat(refunds.acceptPreparation(f.as,prep)).isEqualTo(accepted);
        var wrong=new PointsRefundPort.CashDecision(prep.preparationNo(),prep.inputHash(),2,f.as,f.sub,
                "U","CNY","TEST",45,0,null,stage.command().payment());
        assertThatThrownBy(() -> refunds.authorizeCash(f.as,wrong))
                .hasMessageContaining("POINTS_REFUND_CASH_DECISION_MISMATCH");
        var authorized=refunds.authorizeCash(f.as,new PointsRefundPort.CashDecision(
                prep.preparationNo(),prep.inputHash(),1,f.as,f.sub,"U","CNY","TEST",45,0,
                null,stage.command().payment()));
        assertThat(authorized.phase()).isEqualTo(OrderPointsRefundCoordinator.Phase.CASH_AUTHORIZED);
        String cashNo="CASH:"+f.as;
        when(executions.inspect(f.as)).thenReturn(new RefundExecutionService.Execution(
                new RefundExecutionService.Preparation(f.as,f.sub,"H","CASH_CONFIRMED",45),
                f.plan,cashNo));
        var proof=new ai.neargo.shop.spi.settle.SettlePort.RefundEvidence(cashNo,"REFUND",f.as,f.sub,
                "U","CNY",45,"SUCCESS",45L,1000L,"PROVIDER:"+f.as);
        var confirmedCash=new TransactionTemplate(sourceTx).execute(s -> refunds.recordCashSuccess(f.as,proof));
        assertThat(confirmedCash.phase()).isEqualTo(OrderPointsRefundCoordinator.Phase.CASH_CONFIRMED);
        assertThat(journal.find("ORDER:REFUND_CONFIRM:"+f.as)).isPresent();
        var outcome=new PointsRefundPort.RefundOutcome(
                prep.preparationNo(),"CONFIRMED",7,3,0,0,0,"VOUCHER:"+f.as,false);
        assertThat(refunds.acceptPointsConfirmation(f.as,outcome).phase())
                .isEqualTo(OrderPointsRefundCoordinator.Phase.POINTS_CONFIRMED);
        assertThat(refunds.confirmedTotals(f.sub)).isEqualTo(
                new OrderPointsRefundCoordinator.ConfirmedTotals(7,3));
        var refundedSub=new ai.neargo.shop.trade.entity.OrdSubOrder();
        refundedSub.setSubOrderNo(f.sub);
        refundedSub.setOrderNo(f.proof.orderNo());
        refundedSub.setStatus(ai.neargo.shop.trade.entity.OrdSubOrder.REFUNDED);
        assertThat(orders.returnedOf(refundedSub).pointsReturned()).isEqualTo(7);
        assertThat(orders.returnedOf(refundedSub).pointsClawedBack()).isEqualTo(3);
        assertThatThrownBy(() -> refunds.acceptPointsConfirmation(f.as,new PointsRefundPort.RefundOutcome(
                prep.preparationNo(),"CONFIRMED",8,3,0,0,0,"VOUCHER:"+f.as,false)))
                .hasMessageContaining("POINTS_REFUND_CONFIRMATION_MISMATCH");
        when(executions.inspect(f.as)).thenReturn(new RefundExecutionService.Execution(
                new RefundExecutionService.Preparation(f.as,f.sub,"H","COMMITTED",45),
                f.plan,cashNo));
        assertThat(new TransactionTemplate(sourceTx).execute(s -> refunds.commit(f.as)).phase())
                .isEqualTo(OrderPointsRefundCoordinator.Phase.COMMITTED);
        assertThat(refunds.confirmedTotals(f.sub)).isEqualTo(
                new OrderPointsRefundCoordinator.ConfirmedTotals(7,3));
    }

    @Test void zero_cash_completion_is_a_stable_source_fact_before_points_confirmation() {
        var f=fixture();
        new TransactionTemplate(sourceTx).execute(s -> refunds.stage(f.as,f.plan,f.proof));
        var prep=new PointsRefundPort.RefundPreparation("ZERO:"+f.as,"TYPE",1,"ZERO-HASH",
                0,0,0,0,45,0,0,"COLLECT:"+f.as,"COLLECTED");
        refunds.acceptPreparation(f.as,prep);
        var first=new TransactionTemplate(sourceTx).execute(s -> refunds.recordZeroCash(f.as));
        assertThat(first.phase()).isEqualTo(OrderPointsRefundCoordinator.Phase.ZERO_AUTHORIZED);
        var event="ORDER:ZERO_CASH:"+f.as;
        assertThat(journal.find(event)).isPresent();
        OrderPointsRefundCoordinator.Binding replay=
                new TransactionTemplate(sourceTx).execute(s -> refunds.recordZeroCash(f.as));
        assertThat(replay).isEqualTo(first);
        assertThat(refunds.acceptPointsConfirmation(f.as,new PointsRefundPort.RefundOutcome(
                prep.preparationNo(),"CONFIRMED",0,0,45,0,0,null,false)).phase())
                .isEqualTo(OrderPointsRefundCoordinator.Phase.POINTS_CONFIRMED);
    }

    @Test void definitive_cash_failure_with_collected_offset_enters_compensation_without_refund_commit() {
        var f=fixture();
        var stage=new TransactionTemplate(sourceTx).execute(s -> refunds.stage(f.as,f.plan,f.proof));
        var prep=new PointsRefundPort.RefundPreparation("PREP:"+f.as,"TYPE",1,"HASH",
                0,0,0,0,10,35,0,"COLLECT:"+f.as,"COLLECTED");
        refunds.acceptPreparation(f.as,prep);
        refunds.authorizeCash(f.as,new PointsRefundPort.CashDecision(prep.preparationNo(),
                prep.inputHash(),1,f.as,f.sub,"U","CNY","TEST",35,10,prep.collectionTicket(),
                stage.command().payment()));
        var failed=new ai.neargo.shop.spi.settle.SettlePort.RefundEvidence("CASH:"+f.as,
                "REFUND",f.as,f.sub,"U","CNY",35,"FAILED",null,null,null);
        when(executions.inspect(f.as)).thenReturn(new RefundExecutionService.Execution(
                new RefundExecutionService.Preparation(f.as,f.sub,"H","CASH_PENDING",45),
                f.plan,failed.paymentNo()));
        assertThat(new TransactionTemplate(sourceTx).execute(s -> refunds.recordCashFailure(f.as,failed))
                .phase()).isEqualTo(OrderPointsRefundCoordinator.Phase.COMPENSATING);
        assertThat(journal.find("ORDER:REFUND_CONFIRM:"+f.as)).isEmpty();
    }

    private Fixture fixture() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String root = "R" + suffix, sub = "S" + suffix, as = "A" + suffix;
        jdbc.update(
                "INSERT INTO"
                    + " ord_order(order_no,user_no,status,currency,pay_amount,points_integrated,created_at,updated_at)"
                    + " VALUES(?,'U','PAID','CNY',270,1,NOW(),NOW())",
                root);
        jdbc.update(
                "INSERT INTO"
                    + " ord_sub_order(sub_order_no,order_no,user_no,entity_no,store_no,status,fulfillment,"
                    + "pay_amount,goods_amount,freight_amount,created_at,updated_at)"
                    + " VALUES(?,?,'U','M','ST','FULFILLING','STORE_PICKUP',270,300,0,NOW(),NOW())",
                sub,
                root);
        jdbc.update(
                "INSERT INTO ord_item(sub_order_no,order_no,goods_no,sku_no,title,price,qty,amount,"
                        + "paid_share_minor,supply_store_no,created_at,updated_at)"
                        + " VALUES(?,?,'G1','SKU1','ONE',100,1,100,90,'ST',NOW(),NOW())",
                sub,
                root);
        jdbc.update(
                "INSERT INTO ord_item(sub_order_no,order_no,goods_no,sku_no,title,price,qty,amount,"
                        + "paid_share_minor,supply_store_no,created_at,updated_at)"
                        + " VALUES(?,?,'G2','SKU2','TWO',200,1,200,180,'ST',NOW(),NOW())",
                sub,
                root);
        var ids =
                jdbc.queryForList(
                        "SELECT id FROM ord_item WHERE sub_order_no=? ORDER BY id",
                        Long.class,
                        sub);
        jdbc.update(
                "INSERT INTO"
                    + " ord_after_sale(after_sale_no,sub_order_no,order_no,user_no,entity_no,type,status,"
                    + "refund_mode,refund_minor,approved_minor,reason,created_at,updated_at)"
                    + " VALUES(?,?,?,'U','M','REFUND_ONLY','REFUNDING','CASH',45,45,'OTHER',NOW(),NOW())",
                as,
                sub,
                root);
        var plan = new RefundPlan(sub, List.of(new RefundLine(ids.getFirst(), 1, 45)), 0);
        when(executions.inspect(as))
                .thenReturn(
                        new RefundExecutionService.Execution(
                                new RefundExecutionService.Preparation(
                                        as, sub, "H", "PREPARED", 45),
                                plan,
                                null));
        var payment =
                new PointsRefundPort.PaymentSnapshot(
                        "DIRECT", "TEST", "M", "ST", "PM:1", "SM", null, null, 270);
        var snapshot =
                new OrderPointsBindingService.Snapshot(
                        300,
                        60,
                        30,
                        2,
                        1,
                        "{}",
                        "{}",
                        payment,
                        List.of(
                                new PointsRedeemPort.Item(String.valueOf(ids.get(0)), 100),
                                new PointsRedeemPort.Item(String.valueOf(ids.get(1)), 200)));
        var admission =
                new OrderPointsBindingService.Admission(
                        "REQ", root, sub, "U", "M", "ST", "TYPE", "CNY", "ONLINE", 0, true, true,
                        snapshot);
        when(bindings.find(sub))
                .thenReturn(
                        new OrderPointsBindingService.Binding(
                                "B", admission, OrderPointsBindingService.Phase.BOUND, 0, "H"));
        var route = new PaymentRoute("M", "ST", "PM", "TEST", "SM", "PM:1", "DIRECT");
        var proof =
                new PointsPaymentSnapshotPort.VerifiedPayment(
                        root,
                        "U",
                        "CNY",
                        270,
                        "TEST",
                        root,
                        "TRADE:" + root,
                        Instant.ofEpochMilli(1000),
                        List.of(new PaymentStore(sub, route)));
        return new Fixture(as, sub, plan, proof);
    }

    private record Fixture(
            String as,
            String sub,
            RefundPlan plan,
            PointsPaymentSnapshotPort.VerifiedPayment proof) {}
}
