package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import ai.neargo.shop.spi.points.*;
import ai.neargo.shop.trade.order.points.*;
import ai.neargo.shop.trade.order.refund.service.RefundExecutionService;
import ai.neargo.shop.trade.mapper.TradeMappers.SubOrderMapper;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.order.transition.TradeStatusWriter;
import ai.neargo.shop.trade.order.machine.order.OrderEvent;
import ai.neargo.shop.trade.order.machine.order.OrderContext;
import ai.neargo.shop.trade.order.statemachine.Actor;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@SpringBootTest(
        properties =
                "spring.datasource.url=jdbc:h2:mem:points-closure-flow;"
                        + "MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class PointsOrderClosureFlowTest {
    @Autowired OrderPointsClosureFlowService flow;
    @Autowired OrderPointsFactService facts;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager tx;
    @Autowired TradeStatusWriter statusWriter;
    @Autowired SubOrderMapper subs;
    @MockitoBean OrderPointsBindingService bindings;
    @MockitoBean RefundExecutionService executions;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void finalization_keeps_refund_liability_until_the_longest_window_closes() {
        var f = fixture(System.currentTimeMillis() - 8L * 86_400_000L);
        assertThat(flow.finalizeSubOrder(f.sub())).isTrue();
        assertThat(flow.closeRefundResponsibility(f.sub())).isFalse();
        assertThat(facts.find("ORDER:FINALIZED:" + f.sub())).isPresent();
        assertThat(facts.find("ORDER:REFUND_CLOSED:" + f.sub())).isEmpty();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ord_points_source_fact WHERE sub_order_no=?",
                                Long.class,
                                f.sub()))
                .isEqualTo(2);
    }

    @Test
    void open_after_sale_blocks_both_facts_and_terminal_replay_is_exact() {
        var f = fixture(System.currentTimeMillis() - 16L * 86_400_000L);
        String as = "A" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update(
                "INSERT INTO ord_after_sale(after_sale_no,sub_order_no,order_no,user_no,entity_no,"
                        + "type,status,refund_minor,reason,created_at,updated_at)"
                        + " VALUES(?,?,?,'U','M','REFUND_ONLY','APPLIED',10,'OTHER',NOW(),NOW())",
                as,
                f.sub(),
                f.sub());
        assertThat(flow.finalizeSubOrder(f.sub())).isFalse();
        jdbc.update("UPDATE ord_after_sale SET status='REJECTED' WHERE after_sale_no=?", as);
        assertThat(flow.finalizeSubOrder(f.sub())).isTrue();
        assertThat(flow.closeRefundResponsibility(f.sub())).isTrue();
        assertThat(flow.finalizeSubOrder(f.sub())).isTrue();
        assertThat(flow.closeRefundResponsibility(f.sub())).isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ord_points_source_fact WHERE sub_order_no=?",
                                Long.class,
                                f.sub()))
                .isEqualTo(3);
    }

    @Test
    void full_refund_before_completion_can_finalize_from_verified_refund_terminal() {
        long refundedAt = System.currentTimeMillis() - 16L * 86_400_000L;
        var f = fixture(refundedAt);
        jdbc.update(
                "DELETE FROM ord_status_log WHERE sub_order_no=? AND status='COMPLETED'", f.sub());
        jdbc.update(
                "UPDATE ord_sub_order SET status='REFUNDED',completed_at=NULL WHERE sub_order_no=?",
                f.sub());
        jdbc.update(
                "INSERT INTO ord_status_log(sub_order_no,status,at,created_at)"
                    + " VALUES(?,'REFUNDED',?,NOW())",
                f.sub(),
                refundedAt);
        String as = "A" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update(
                "INSERT INTO ord_after_sale(after_sale_no,sub_order_no,order_no,user_no,entity_no,"
                        + "type,status,refund_minor,reason,created_at,updated_at)"
                        + " VALUES(?,?,?,'U','M','REFUND_ONLY','REFUNDED',100,'OTHER',NOW(),NOW())",
                as,
                f.sub(),
                f.sub());
        long id =
                jdbc.queryForObject(
                        "SELECT id FROM ord_item WHERE sub_order_no=?", Long.class, f.sub());
        var command =
                new PointsRefundPort.RefundCommand(
                        as,
                        f.sub(),
                        "U",
                        "TYPE",
                        "CNY",
                        0,
                        100,
                        100,
                        List.of(new PointsRefundPort.RefundLine(String.valueOf(id), 100)),
                        new PointsRefundPort.PaymentSnapshot(
                                "DIRECT", "TEST", "M", "ST", "PM:1", "SM", "TRADE", "OUT", 100));
        jdbc.update(
                "INSERT INTO"
                    + " ord_points_refund_binding(after_sale_no,sub_order_no,phase,source_version,command_json)"
                    + " VALUES(?,?,'COMMITTED',0,?)",
                as,
                f.sub(),
                json.writeValueAsString(command));
        when(executions.inspect(as))
                .thenReturn(
                        new RefundExecutionService.Execution(
                                new RefundExecutionService.Preparation(
                                        as, f.sub(), "HASH", "COMMITTED", 100),
                                null,
                                null));

        abortedRefund(f); // The replaced original attempt is closed; only its successor refunded goods.
        assertThat(flow.finalizeSubOrder(f.sub())).isTrue();
        assertThat(flow.closeRefundResponsibility(f.sub())).isTrue();
    }

    @Test
    void manually_closed_aborted_refund_does_not_block_normal_completion() {
        var f = fixture(System.currentTimeMillis() - 16L * 86_400_000L);
        String as = abortedRefund(f);
        jdbc.update("UPDATE ord_after_sale SET status='REFUNDING' WHERE after_sale_no=?", as);
        assertThat(flow.finalizeSubOrder(f.sub())).isFalse();
        jdbc.update("UPDATE ord_after_sale SET status='CLOSED' WHERE after_sale_no=?", as);
        assertThat(flow.finalizeSubOrder(f.sub())).isTrue();
        assertThat(flow.closeRefundResponsibility(f.sub())).isTrue();
    }

    private String abortedRefund(Fixture f) {
        String as = "A" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO ord_after_sale(after_sale_no,sub_order_no,order_no,user_no,entity_no,type,status,refund_minor,reason,created_at,updated_at)"
                + " VALUES(?,?,?,'U','M','REFUND_ONLY','CLOSED',100,'OTHER',NOW(),NOW())", as, f.sub(), f.sub());
        jdbc.update("INSERT INTO ord_points_refund_binding(after_sale_no,sub_order_no,phase,source_version,command_json) VALUES(?,?,'ABORTED',0,'{}')", as, f.sub());
        when(executions.inspect(as)).thenReturn(new RefundExecutionService.Execution(
                new RefundExecutionService.Preparation(as,f.sub(),"HASH","ABORTED",100),null,null));
        return as;
    }

    @Test
    void normal_completion_writes_the_final_fact_in_the_same_source_flow() {
        var f = fixture(System.currentTimeMillis() - 8L * 86_400_000L);
        jdbc.update("DELETE FROM ord_status_log WHERE sub_order_no=? AND status='COMPLETED'", f.sub());
        jdbc.update("UPDATE ord_sub_order SET status='FULFILLING',completed_at=NULL WHERE sub_order_no=?", f.sub());
        new TransactionTemplate(tx).executeWithoutResult(s -> {
            OrdSubOrder sub = subs.selectOne(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<OrdSubOrder>()
                    .eq("sub_order_no", f.sub()));
            statusWriter.order(sub, OrderEvent.DELIVERED_BY_MERCHANT, Actor.MERCHANT,
                    "M", "delivered", OrderContext.authorizedOnly());
        });
        assertThat(facts.find("ORDER:FINALIZED:" + f.sub())).isPresent();
    }

    private Fixture fixture(long completedAt) {
        String sub = "S" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update(
                "INSERT INTO"
                    + " ord_order(order_no,user_no,status,currency,pay_amount,points_integrated,created_at,updated_at)"
                    + " VALUES(?,'U','PAID','CNY',100,1,NOW(),NOW())",
                sub);
        jdbc.update(
                "INSERT INTO ord_sub_order(sub_order_no,order_no,user_no,entity_no,store_no,status,"
                    + "fulfillment,pay_amount,goods_amount,freight_amount,completed_at,created_at,updated_at)"
                    + " VALUES(?,?,'U','M','ST','COMPLETED','STORE_PICKUP',100,100,0,?,NOW(),NOW())",
                sub,
                sub,
                completedAt);
        jdbc.update(
                "INSERT INTO ord_status_log(sub_order_no,status,at,created_at)"
                        + " VALUES(?,'COMPLETED',?,NOW())",
                sub,
                completedAt);
        jdbc.update(
                "INSERT INTO ord_item(sub_order_no,order_no,goods_no,sku_no,title,price,qty,amount,"
                        + "paid_share_minor,supply_store_no,created_at,updated_at)"
                        + " VALUES(?,?,'G','SKU','ITEM',100,1,100,100,'ST',NOW(),NOW())",
                sub,
                sub);
        long id =
                jdbc.queryForObject(
                        "SELECT id FROM ord_item WHERE sub_order_no=?", Long.class, sub);
        var payment =
                new PointsRefundPort.PaymentSnapshot(
                        "DIRECT", "TEST", "M", "ST", "PM:1", "SM", "TRADE", "OUT", 100);
        var snapshot =
                new OrderPointsBindingService.Snapshot(
                        100,
                        0,
                        0,
                        1,
                        1,
                        "{}",
                        "{}",
                        payment,
                        List.of(new PointsRedeemPort.Item(String.valueOf(id), 100)));
        var admission =
                new OrderPointsBindingService.Admission(
                        "REQ", sub, sub, "U", "M", "ST", "TYPE", "CNY", "ONLINE", 0, true, true,
                        snapshot);
        when(bindings.find(sub))
                .thenReturn(
                        new OrderPointsBindingService.Binding(
                                "B", admission, OrderPointsBindingService.Phase.BOUND, 0, "H"));
        Instant at = Instant.ofEpochMilli(completedAt - 86_400_000L);
        var paid =
                PointsLifecycleFacts.sign(
                        new PointsFactPort.LifecycleFact(
                                "ORDER:PAID:" + sub,
                                null,
                                sub,
                                "U",
                                "TYPE",
                                0,
                                PointsFactPort.FactKind.PAID,
                                100,
                                0,
                                List.of(
                                        new PointsFactPort.LineSnapshot(
                                                String.valueOf(id), 1, 100, 0)),
                                at,
                                payment),
                        json::writeValueAsString);
        new TransactionTemplate(tx)
                .executeWithoutResult(
                        s ->
                                facts.append(
                                        new PointsOrderEvent(
                                                sub,
                                                sub,
                                                "M",
                                                "ST",
                                                "U",
                                                "TYPE",
                                                "CNY",
                                                0,
                                                at,
                                                new SourceFactsPort.Fact(
                                                        0, paid, null, null, null, null))));
        return new Fixture(sub);
    }

    private record Fixture(String sub) {}
}
