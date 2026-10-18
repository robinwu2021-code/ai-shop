package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.*;

import ai.neargo.shop.spi.points.PointsEarnPort;
import ai.neargo.shop.spi.points.PointsRedeemPort;
import ai.neargo.shop.spi.points.PointsRefundPort;
import ai.neargo.shop.spi.user.MerchantQueryPort.PaymentRoute;
import ai.neargo.shop.trade.order.points.OrderPointsTargetFactory;
import ai.neargo.shop.trade.order.points.PointsOrderPlacementCoordinator;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

/** Real order/item/status/outbox writes; fixtures use synthetic identities only. */
@SpringBootTest
@ActiveProfiles("test")
class PointsOrderSourceOrdersFlowTest {
    @Autowired PointsOrderPlacementCoordinator.SourceOrders source;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired List<ai.neargo.shop.event.OutboxConsumer> consumers;

    @Test
    void durable_wake_suspends_the_dispatchers_source_transaction() {
        var wake =
                consumers.stream()
                        .filter(
                                c ->
                                        c.supports(
                                                ai.neargo.shop.spi.points
                                                        .OrderPointsPlacementWakeEvent.TYPE))
                        .findFirst()
                        .orElseThrow();
        var event = new ai.neargo.shop.event.SysOutbox();
        event.setPayload(
                new tools.jackson.databind.ObjectMapper()
                        .writeValueAsString(
                                new ai.neargo.shop.spi.points.OrderPointsPlacementWakeEvent(
                                        "QA-NOT-CREATED")));
        new TransactionTemplate(transactionManager).executeWithoutResult(s -> wake.consume(event));
    }

    @Test
    void confirmed_store_share_updates_original_order_and_lines_atomically() {
        var f = fixture();
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(s -> source.finish(f.draft, f.outcome));
        assertThat(
                        jdbc.queryForObject(
                                "select status from ord_order where order_no=?",
                                String.class,
                                f.root))
                .isEqualTo("WAIT_PAY");
        assertThat(
                        jdbc.queryForObject(
                                "select pay_amount from ord_order where order_no=?",
                                Long.class,
                                f.root))
                .isEqualTo(90);
        assertThat(
                        jdbc.queryForObject(
                                "select points_deduct from ord_sub_order where sub_order_no=?",
                                Integer.class,
                                f.sub))
                .isEqualTo(20);
        assertThat(
                        jdbc.queryForObject(
                                "select points_deduct_minor from ord_sub_order where"
                                        + " sub_order_no=?",
                                Long.class,
                                f.sub))
                .isEqualTo(10);
        assertThat(
                        jdbc.queryForObject(
                                "select paid_share_minor from ord_item where id=?",
                                Long.class,
                                f.item))
                .isEqualTo(90);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from sys_outbox where aggregate_id=? and"
                                        + " event_type='ORDER_CREATED'",
                                Long.class,
                                f.root))
                .isEqualTo(1);
    }

    @Test
    void wrong_store_share_cannot_make_an_order_payable() {
        var f = fixture();
        var changed =
                new PointsRedeemPort.HoldOutcome(
                        "H:" + f.root,
                        "TYPE",
                        20,
                        10,
                        "HELD",
                        null,
                        List.of(new PointsRedeemPort.Share(f.sub, "M", "OTHER_STORE", 20, 10)));
        assertThatThrownBy(
                        () ->
                                new TransactionTemplate(transactionManager)
                                        .executeWithoutResult(s -> source.finish(f.draft, changed)))
                .hasMessageContaining("POINTS_SOURCE_SHARES_INVALID");
        assertThat(
                        jdbc.queryForObject(
                                "select status from ord_order where order_no=?",
                                String.class,
                                f.root))
                .isEqualTo("POINTS_PENDING");
        assertThat(
                        jdbc.queryForObject(
                                "select paid_share_minor from ord_item where id=?",
                                Long.class,
                                f.item))
                .isEqualTo(100);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from sys_outbox where aggregate_id=? and"
                                        + " event_type='ORDER_CREATED'",
                                Long.class,
                                f.root))
                .isZero();
    }

    private Fixture fixture() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String root = "QA-POINTS-" + suffix;
        String sub = root + "-01";
        String store = "ST-" + suffix;
        jdbc.update(
                "insert into"
                    + " ord_order(order_no,user_no,status,pay_amount,goods_amount,currency,points_integrated,created_at,updated_at)"
                    + " values(?, 'U',"
                    + " 'POINTS_PENDING',100,100,'CNY',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                root);
        jdbc.update(
                "insert into"
                    + " ord_sub_order(sub_order_no,order_no,user_no,entity_no,store_no,status,pay_amount,goods_amount,freight_amount,discount_amount,created_at,updated_at)"
                    + " values(?,?,'U','M',?,'WAIT_PAY',100,100,0,0,"
                    + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                sub,
                root,
                store);
        jdbc.update(
                "insert into"
                    + " ord_item(sub_order_no,order_no,goods_no,sku_no,qty,amount,paid_share_minor,supply_store_no,created_at,updated_at)"
                    + " values(?,?,'G','SKU',1,100,100,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                sub,
                root,
                store);
        String item =
                String.valueOf(
                        jdbc.queryForObject(
                                "select id from ord_item where sub_order_no=?", Long.class, sub));
        var target =
                new PointsRedeemPort.Target(
                        sub,
                        "M",
                        store,
                        100,
                        "DIRECT",
                        "TEST",
                        "V1",
                        "SM",
                        null,
                        List.of(new PointsRedeemPort.Item(item, 100)));
        var route = new PaymentRoute("M", store, "PM", "TEST", "SM", "V1", "DIRECT");
        var payment =
                new PointsRefundPort.PaymentSnapshot(
                        "DIRECT", "TEST", "M", store, "V1", "SM", null, null, 100);
        var candidate =
                new PointsOrderPlacementCoordinator.Candidate(
                        new OrderPointsTargetFactory.PlacementTarget(target, payment, route),
                        0,
                        true,
                        true,
                        List.of(new PointsEarnPort.EarnLine(item, "G", "C", 1, 100, 0, null)));
        var draft =
                new PointsOrderPlacementCoordinator.Draft(
                        root,
                        "U",
                        "CN",
                        "CNY",
                        "ONLINE",
                        "H5",
                        20,
                        100,
                        "BODY",
                        List.of(candidate),
                        List.of(sub));
        var outcome =
                new PointsRedeemPort.HoldOutcome(
                        "H:" + root,
                        "TYPE",
                        20,
                        10,
                        "HELD",
                        null,
                        List.of(new PointsRedeemPort.Share(sub, "M", store, 20, 10)));
        return new Fixture(root, sub, item, draft, outcome);
    }

    private record Fixture(
            String root,
            String sub,
            String item,
            PointsOrderPlacementCoordinator.Draft draft,
            PointsRedeemPort.HoldOutcome outcome) {}
}
