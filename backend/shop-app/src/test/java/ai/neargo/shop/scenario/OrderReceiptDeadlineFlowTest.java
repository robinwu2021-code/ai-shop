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
        OrderReceiptFixture.due(jdbc, sub, now - 3 * DAY);
        signed(shipment, now - 7 * DAY);
        assertThat(auto.completeAuto(sub, now - 1, 15, 7)).isFalse();
        assertThat(status("ord_shipment", "shipment_no", shipment)).isEqualTo("DELIVERED");
        assertThat(auto.completeAuto(sub, now, 15, 7)).isTrue();
        assertThat(status("ord_shipment", "shipment_no", shipment)).isEqualTo("RECEIVED");
        assertThat(status("ord_sub_order", "sub_order_no", sub)).isEqualTo("COMPLETED");
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
        OrderReceiptFixture.due(jdbc, sub, now - 3 * DAY);
        signed(first, now - 7 * DAY);
        assertThat(auto.completeAuto(sub, now, 15, 7)).isFalse();
        assertThat(status("ord_shipment", "shipment_no", first)).isEqualTo("RECEIVED");
        assertThat(status("ord_shipment", "shipment_no", last)).isEqualTo("SHIPPED");
        assertThat(status("ord_sub_order", "sub_order_no", sub)).isEqualTo("FULFILLING");
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

    private void signed(String shipment, long at) {
        jdbc.update("UPDATE ord_shipment SET status='DELIVERED',signed_at=? WHERE shipment_no=?", at, shipment);
    }

    private String status(String table, String key, String no) {
        return jdbc.queryForObject("SELECT status FROM " + table + " WHERE " + key + "=?", String.class, no);
    }
}
