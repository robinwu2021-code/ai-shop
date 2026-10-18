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
