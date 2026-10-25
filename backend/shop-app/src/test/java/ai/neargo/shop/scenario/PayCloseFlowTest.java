package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.spi.pay.PayQueryPort;
import ai.neargo.shop.spi.pay.PaymentPort;
import ai.neargo.shop.spi.pay.PaymentPort.InternalCreatePaymentCmd;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.spi.settle.SettlePort.PaymentSettled;
import ai.neargo.shop.support.port.FakePayQueryPort;
import ai.neargo.shop.trade.service.OrderService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * AC8：关单关到支付这一层，关前必查单（R18；TDD-支付服务-I3支付单退款流水 S7）。
 *
 * <p>此前订单超时关单一行支付域都不碰：未终态的尝试永远停在 PENDING（对账待查集合只增不减），
 * 关掉的单仍能被渠道回执改成功。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:pay-close-flow;MODE=MySQL;"
        + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class PayCloseFlowTest {

    @Autowired private PaymentPort payments;
    @Autowired private PaymentLedgerService ledger;
    @Autowired private SettlePort settle;
    @Autowired private OrderService orders;
    @Autowired private FakePayQueryPort channel;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void reset() {
        channel.reset();
    }

    private String opened() {
        String no = "PO-CL-" + UUID.randomUUID().toString().substring(0, 8);
        Long store = jdbc.queryForObject("SELECT id FROM mch_store ORDER BY id LIMIT 1", Long.class);
        payments.createPayment(new InternalCreatePaymentCmd(PaymentPort.MALL_ORDER, no, no, store, "门店", "主体", "CN",
                1990, "CNY", "goods", System.currentTimeMillis() + 900_000, "AGGREGATED"));
        ledger.open(new SettlePort.PaymentOpen(no, "U-" + no, null, "TEST", 1990, "goods"));
        return no;
    }

    private Map<String, Object> order(String no) {
        return jdbc.queryForMap("SELECT status, closed_at FROM pay_order WHERE out_order_no=?", no);
    }

    private String attempt(String no) {
        return jdbc.queryForObject("SELECT status FROM stl_payment WHERE out_trade_no=?", String.class, no);
    }

    @Test
    @DisplayName("★★★ 查单说没有这笔 → 支付单 CLOSED、未终态的尝试一并 CLOSED（对账待查集合不再只增不减）")
    void closeUnpaid() {
        String no = opened();
        channel.answer(new PayQueryPort.Result(true, false, false, 0, null));
        assertThat(payments.closePayment(PaymentPort.MALL_ORDER, no, "支付超时").status()).isEqualTo("CLOSED");
        assertThat(order(no)).containsEntry("status", "CLOSED");
        assertThat(order(no).get("closed_at")).isNotNull();
        assertThat(attempt(no)).isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("★★★ 关前查单发现已付 → 不关，按结果入口入账（支付单成功）—— 关掉一笔收到的钱就是掉单")
    void closeFindsPaid() {
        String no = opened();
        channel.answer(new PayQueryPort.Result(true, true, true, 1990, "TX-" + no, "CNY"));
        assertThat(payments.closePayment(PaymentPort.MALL_ORDER, no, "支付超时").status()).isEqualTo("SUCCEEDED");
        assertThat(attempt(no)).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("★★★ 订单超时关单 → 提交后关支付单；之后渠道回执到账 → LATE_PAYMENT，支付单仍 CLOSED")
    void orderCloseClosesPayOrderAndLatePaymentIsAnException() {
        String no = opened();
        channel.answer(new PayQueryPort.Result(true, false, false, 0, null));
        jdbc.update("INSERT INTO ord_order (order_no, user_no, status, pay_amount, currency, tenant_no, created_at,"
                + " updated_at, version, deleted, pay_deadline_at) VALUES (?, ?, 'WAIT_PAY', 1990, 'CNY', 'MAIN',"
                + " NOW(), NOW(), 0, 0, 1)", no, "U-" + no);
        orders.closeUnpaid(no, "支付超时");
        assertThat(order(no)).containsEntry("status", "CLOSED");
        assertThat(attempt(no)).isEqualTo("CLOSED");

        settle.settlePayment(new PaymentSettled(no, "TEST", "TX-LATE-" + no, 1990, System.currentTimeMillis(), "CNY"));
        assertThat(order(no)).containsEntry("status", "CLOSED");
        assertThat(jdbc.queryForObject("SELECT kind FROM pay_exception WHERE stl_payment_id=(SELECT id FROM stl_payment"
                + " WHERE out_trade_no=?)", String.class, no)).isEqualTo("LATE_PAYMENT");
    }
}
