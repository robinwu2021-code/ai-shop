package ai.neargo.shop.scenario;

import ai.neargo.shop.support.OrderReceiptFixture;
import ai.neargo.shop.trade.order.command.OrderActor;
import ai.neargo.shop.trade.order.command.ShipCommand;
import ai.neargo.shop.trade.order.service.OrderShippingService;
import ai.neargo.shop.trade.order.service.receipt.OrderReceiptCompleter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Auto receipt must use current package facts and exact deadlines within its real per-child transaction. */
@SpringBootTest(properties = {
        "spring.datasource.url=${order.phase1.test.url:jdbc:h2:mem:receipt-deadlines;MODE=MySQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}",
        "spring.datasource.hikari.transaction-isolation=TRANSACTION_REPEATABLE_READ"
})
@ActiveProfiles("test")
class OrderReceiptDeadlineFlowTest {
    private static final long DAY = 86_400_000L;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private OrderShippingService shipping;
    @Autowired private OrderReceiptCompleter auto;

    @Test
    void signedDeadlineIsInclusiveAndUsesActualPackageWithoutLogisticsProjection() {
        String sub = OrderReceiptFixture.seed(jdbc, "RC-DEADLINE-BUYER");
        String shipment = OrderReceiptFixture.ship(shipping, sub);
        long now = System.currentTimeMillis() / 1000 * 1000;
        OrderReceiptFixture.due(jdbc, sub, now - 8 * DAY);
        signed(shipment, now - 7 * DAY);
        trace(sub, now - 8 * DAY + 60_000L, now - 7 * DAY);
        assertThat(auto.completeAuto(sub, now - 1, 15, 7)).isFalse();
        assertThat(status("ord_shipment", "shipment_no", shipment)).isEqualTo("DELIVERED");
        assertThat(auto.completeAuto(sub, now, 15, 7)).isTrue();
        assertThat(status("ord_shipment", "shipment_no", shipment)).isEqualTo("RECEIVED");
        assertThat(status("ord_sub_order", "sub_order_no", sub)).isEqualTo("COMPLETED");
    }

    @Test
    void reusedWaybillCannotTriggerSignedDeadline() {
        String sub = OrderReceiptFixture.seed(jdbc, "RC-DEADLINE-BUYER");
        String shipment = OrderReceiptFixture.ship(shipping, sub);
        long now = System.currentTimeMillis() / 1000 * 1000;
        OrderReceiptFixture.due(jdbc, sub, now - 8 * DAY);
        signed(shipment, now - 7 * DAY);
        trace(sub, now - 9 * DAY, now - 7 * DAY);
        assertThat(auto.completeAuto(sub, now, 15, 7)).isFalse();
        assertThat(status("ord_shipment", "shipment_no", shipment)).isEqualTo("DELIVERED");
        assertThat(status("ord_sub_order", "sub_order_no", sub)).isEqualTo("FULFILLING");
    }

    @Test
    void shippingDeadlineReceivesActualCreatedPackageExactlyOnce() {
        String sub = OrderReceiptFixture.seed(jdbc, "RC-DEADLINE-BUYER");
        String shipment = OrderReceiptFixture.ship(shipping, sub);
        long now = System.currentTimeMillis() / 1000 * 1000;
        OrderReceiptFixture.due(jdbc, sub, now - 15 * DAY);
        assertThat(auto.completeAuto(sub, now - 1, 15, 7)).isFalse();
        assertThat(auto.completeAuto(sub, now, 15, 7)).isTrue();
        assertThat(status("ord_shipment", "shipment_no", shipment)).isEqualTo("RECEIVED");
        assertThat(auto.completeAuto(sub, now, 15, 7)).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ord_status_log WHERE sub_order_no=? "
                + "AND aggregate_type='ORDER' AND event='RECEIPT_AUTO_CONFIRMED'", Long.class, sub)).isEqualTo(1);
    }

    @Test
    void oneSignedPackageCannotReceiveAnotherUnduePackage() {
        String sub = OrderReceiptFixture.seed(jdbc, "RC-DEADLINE-BUYER");
        String first = OrderReceiptFixture.ship(shipping, sub);
        String last = OrderReceiptFixture.extraPackage(jdbc, sub);
        long now = System.currentTimeMillis();
        OrderReceiptFixture.due(jdbc, sub, now - 8 * DAY);
        jdbc.update("UPDATE ord_shipment SET created_at=? WHERE shipment_no=?",
                OrderReceiptFixture.physicalAt(now - 3 * DAY), last);
        signed(first, now - 7 * DAY);
        trace(sub, now - 8 * DAY + 60_000L, now - 7 * DAY);
        assertThat(auto.completeAuto(sub, now, 15, 7)).isFalse();
        assertThat(status("ord_shipment", "shipment_no", first)).isEqualTo("RECEIVED");
        assertThat(status("ord_shipment", "shipment_no", last)).isEqualTo("SHIPPED");
        assertThat(status("ord_sub_order", "sub_order_no", sub)).isEqualTo("FULFILLING");
        jdbc.update("UPDATE ord_shipment SET created_at=? WHERE shipment_no=?",
                OrderReceiptFixture.physicalAt(now - 8 * DAY), last);
        signed(last, now - 7 * DAY);
        assertThat(auto.completeAuto(sub, now, 15, 7)).isTrue();
        assertThat(status("ord_sub_order", "sub_order_no", sub)).isEqualTo("COMPLETED");
    }

    @Test
    void oldChildDispatchCannotReceiveFreshHistoricalPackage() {
        String sub = OrderReceiptFixture.seed(jdbc, "RC-DEADLINE-BUYER");
        String first = OrderReceiptFixture.ship(shipping, sub);
        long now = System.currentTimeMillis();
        OrderReceiptFixture.due(jdbc, sub, now - 20 * DAY);
        jdbc.update("UPDATE ord_shipment SET created_at=? WHERE shipment_no=?",
                OrderReceiptFixture.physicalAt(now - 20 * DAY), first);
        String last = OrderReceiptFixture.extraPackage(jdbc, sub);
        assertThat(auto.completeAuto(sub, now, 15, 7)).isFalse();
        assertThat(status("ord_shipment", "shipment_no", first)).isEqualTo("RECEIVED");
        assertThat(status("ord_shipment", "shipment_no", last)).isEqualTo("SHIPPED");
        assertThat(status("ord_sub_order", "sub_order_no", sub)).isEqualTo("FULFILLING");
    }

    @Test
    void correctionPreservesOriginalPhysicalDispatchDeadline() {
        String sub = OrderReceiptFixture.seed(jdbc, "RC-DEADLINE-BUYER");
        String first = OrderReceiptFixture.ship(shipping, sub);
        long now = System.currentTimeMillis() / 1000 * 1000;
        OrderReceiptFixture.due(jdbc, sub, now - 15 * DAY);
        jdbc.update("UPDATE ord_shipment SET created_at=? WHERE shipment_no=?",
                OrderReceiptFixture.physicalAt(now - 15 * DAY), first);
        String replacement = shipping.ship(sub, new ShipCommand(List.of(), "SF", "SF" + sub.substring(2, 21) + "X"),
                new OrderActor("RC-MERCHANT", "RC-STORE")).shipmentId();
        assertThat(status("ord_shipment", "shipment_no", first)).isEqualTo("VOIDED");
        assertThat(auto.completeAuto(sub, now, 15, 7)).isTrue();
        assertThat(status("ord_shipment", "shipment_no", replacement)).isEqualTo("RECEIVED");
        assertThat(status("ord_sub_order", "sub_order_no", sub)).isEqualTo("COMPLETED");
    }

    @Test
    void actualLegacyDispatchWithoutPackageStillAutoCompletes() {
        String sub = OrderReceiptFixture.seed(jdbc, "RC-DEADLINE-BUYER");
        long now = System.currentTimeMillis();
        jdbc.update("UPDATE ord_sub_order SET status='FULFILLING',express_company='SF',express_no=? "
                + "WHERE sub_order_no=?", "SF" + sub.substring(2, 22), sub);
        jdbc.update("INSERT INTO ord_status_log (sub_order_no,status,label,operator_type,at,created_at) "
                + "VALUES (?,'FULFILLING','商家发货','MERCHANT',?,CURRENT_TIMESTAMP)", sub, now - 15 * DAY);
        assertThat(auto.completeAuto(sub, now - 1, 15, 0)).isFalse();
        assertThat(auto.completeAuto(sub, now, 15, 0)).isTrue();
        assertThat(status("ord_sub_order", "sub_order_no", sub)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ord_shipment WHERE sub_order_no=?",
                Long.class, sub)).isZero();
    }

    @Test
    void legacySignatureBeforeDispatchCannotCompleteButLaterSignatureCan() {
        String sub = OrderReceiptFixture.seed(jdbc, "RC-DEADLINE-BUYER");
        long now = System.currentTimeMillis();
        long shippedAt = now - 2 * DAY;
        jdbc.update("UPDATE ord_sub_order SET created_at=? WHERE sub_order_no=?",
                OrderReceiptFixture.physicalAt(now - 4 * DAY), sub);
        jdbc.update("UPDATE ord_sub_order SET status='FULFILLING',express_company='STO',express_no=? "
                + "WHERE sub_order_no=?", "STO" + sub.substring(2, 22), sub);
        jdbc.update("INSERT INTO ord_status_log (sub_order_no,status,label,operator_type,at,created_at) "
                + "VALUES (?,'FULFILLING','商家发货','MERCHANT',?,CURRENT_TIMESTAMP)", sub, shippedAt);
        jdbc.update("INSERT INTO lgs_waybill (shipment_no,biz_ref,carrier,waybill_no,status,signed_at,"
                + "created_at,updated_at) VALUES (?,?, 'STO',?,'DELIVERED',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                "LGS-" + sub, sub, "STO" + sub.substring(2, 22), now - 3 * DAY);
        jdbc.update("INSERT INTO lgs_waybill_node (shipment_no,at,text,created_at) "
                + "VALUES (?,?, '旧订单轨迹',CURRENT_TIMESTAMP)", "LGS-" + sub, now - 5 * DAY);
        assertThat(auto.completeAuto(sub, now, 15, 1)).isFalse();
        assertThat(status("ord_sub_order", "sub_order_no", sub)).isEqualTo("FULFILLING");
        jdbc.update("UPDATE lgs_waybill SET signed_at=? WHERE biz_ref=?", now - DAY, sub);
        assertThat(auto.completeAuto(sub, now, 15, 1)).isFalse();
        jdbc.update("UPDATE lgs_waybill_node SET at=? WHERE shipment_no=?", now - DAY - 60_000L, "LGS-" + sub);
        assertThat(auto.completeAuto(sub, now, 15, 1)).isTrue();
        assertThat(status("ord_sub_order", "sub_order_no", sub)).isEqualTo("COMPLETED");
    }

    private void signed(String shipment, long at) {
        jdbc.update("UPDATE ord_shipment SET status='DELIVERED',signed_at=? WHERE shipment_no=?", at, shipment);
    }

    private void trace(String sub, long firstAt, long signedAt) {
        jdbc.update("INSERT INTO lgs_waybill (shipment_no,biz_ref,carrier,waybill_no,status,signed_at,"
                + "created_at,updated_at) VALUES (?,?, 'SF',?,'DELIVERED',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                "LGS-" + sub, sub, "SF" + sub.substring(2, 22), signedAt);
        jdbc.update("INSERT INTO lgs_waybill_node (shipment_no,at,text,created_at) "
                + "VALUES (?,?, '已揽收',CURRENT_TIMESTAMP)", "LGS-" + sub, firstAt);
    }

    private String status(String table, String key, String no) {
        return jdbc.queryForObject("SELECT status FROM " + table + " WHERE " + key + "=?", String.class, no);
    }
}
