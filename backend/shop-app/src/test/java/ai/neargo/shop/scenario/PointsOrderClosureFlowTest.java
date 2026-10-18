package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import ai.neargo.shop.spi.points.*;
import ai.neargo.shop.trade.order.points.*;

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
    @MockitoBean OrderPointsBindingService bindings;
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
