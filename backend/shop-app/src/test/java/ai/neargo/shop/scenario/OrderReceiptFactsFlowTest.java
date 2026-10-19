package ai.neargo.shop.scenario;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.support.OrderReceiptFixture;
import ai.neargo.shop.trade.order.command.OrderActor;
import ai.neargo.shop.trade.order.service.OrderReceiptService;
import ai.neargo.shop.trade.order.service.OrderShippingService;
import ai.neargo.shop.trade.order.service.receipt.OrderReceiptCompleter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Receipt eligibility rejects inconsistent persisted history before any package or completion write. */
@SpringBootTest
@ActiveProfiles("test")
class OrderReceiptFactsFlowTest {
    private static final String BUYER = "RC-FACT-BUYER";
    @Autowired private JdbcTemplate jdbc;
    @Autowired private OrderShippingService shipping;
    @Autowired private OrderReceiptService receipts;
    @Autowired private OrderReceiptCompleter auto;

    @Test
    void unpaidParentCannotCompleteEvenWhenChildLooksDispatched() {
        String sub = OrderReceiptFixture.seed(jdbc, BUYER);
        String shipment = OrderReceiptFixture.ship(shipping, sub);
        jdbc.update("UPDATE ord_order SET status='WAIT_PAY' WHERE order_no=?", "O-" + sub);
        assertThatThrownBy(() -> receipts.confirmCurrent(sub, OrderActor.buyer(BUYER)))
                .isInstanceOf(BizException.class);
        assertThat(packageStatus(shipment)).isEqualTo("CREATED");
    }

    @Test
    void closedParentCannotAutoCompleteFromStaleDispatchFacts() {
        String sub = OrderReceiptFixture.seed(jdbc, BUYER);
        String shipment = OrderReceiptFixture.ship(shipping, sub);
        long now = System.currentTimeMillis();
        OrderReceiptFixture.due(jdbc, sub, now - 20L * 86_400_000L);
        jdbc.update("UPDATE ord_order SET status='CLOSED' WHERE order_no=?", "O-" + sub);
        assertThat(auto.completeAuto(sub, now, 15, 7)).isFalse();
        assertThat(packageStatus(shipment)).isEqualTo("CREATED");
    }

    @Test
    void createdPackageWithNoDispatchedQuantityCannotBeReceived() {
        String sub = OrderReceiptFixture.seed(jdbc, BUYER);
        String shipment = OrderReceiptFixture.ship(shipping, sub);
        jdbc.update("UPDATE ord_item SET shipped_qty=0 WHERE sub_order_no=?", sub);
        assertThatThrownBy(() -> receipts.confirmReceipt(sub, shipment, OrderActor.buyer(BUYER)))
                .isInstanceOf(BizException.class);
        assertThat(packageStatus(shipment)).isEqualTo("CREATED");
    }

    private String packageStatus(String no) {
        return jdbc.queryForObject("SELECT status FROM ord_shipment WHERE shipment_no=?", String.class, no);
    }
}
