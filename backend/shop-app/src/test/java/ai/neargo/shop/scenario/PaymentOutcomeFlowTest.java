package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.spi.pay.PaymentPort;
import ai.neargo.shop.spi.pay.PaymentPort.InternalCreatePaymentCmd;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.spi.settle.SettlePort.PaymentSettled;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * AC4：结果唯一入口（TDD-支付服务-I3支付单退款流水 S4）。回调、补查、掉单巡检、餐饮刷新都落到同一处，
 * 由它决定支付单能不能进 SUCCEEDED。
 *
 * <p>此前只有通道尝试一层：已关闭的尝试被渠道回执静默改成功（R18）；应付金额被渠道实收覆盖（R20），
 * 事后再也看不出「应收 1990、实收 2000」。
 */
// 支付服务 I3 的六个场景类共用一个上下文（同一个库）：各自用随机单号，互不干扰；每类一个上下文会把全量测试撑爆堆
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:pay-i3;MODE=MySQL;"
        + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class PaymentOutcomeFlowTest {

    @Autowired private PaymentPort payments;
    @Autowired private PaymentLedgerService ledger;
    @Autowired private SettlePort settle;
    @Autowired private JdbcTemplate jdbc;

    private String opened(long amount) {
        String no = "PO-OC-" + UUID.randomUUID().toString().substring(0, 8);
        Long store = jdbc.queryForObject("SELECT id FROM mch_store ORDER BY id LIMIT 1", Long.class);
        payments.createPayment(new InternalCreatePaymentCmd(PaymentPort.MALL_ORDER, no, no, store, "门店", "主体", "CN",
                amount, "CNY", "goods", System.currentTimeMillis() + 900_000, "AGGREGATED"));
        assertThat(ledger.open(new SettlePort.PaymentOpen(no, "U-" + no, null, "TEST", amount, "goods"))).isEqualTo(no);
        return no;
    }

    private Map<String, Object> order(String no) {
        return jdbc.queryForMap("SELECT * FROM pay_order WHERE out_order_no=?", no);
    }

    private PaymentSettled paid(String no, long amount) {
        return new PaymentSettled(no, "TEST", "TX-" + no, amount, System.currentTimeMillis(), "CNY");
    }

    @Test
    @DisplayName("★★★ 同一笔回执来两次：支付单成功一次，实收只记一次")
    void notifyTwiceBooksOnce() {
        String no = opened(1990);
        assertThat(settle.settlePayment(paid(no, 1990))).isEqualTo(no);
        assertThat(settle.settlePayment(paid(no, 1990))).isEqualTo(no);
        assertThat(order(no)).containsEntry("status", "SUCCEEDED").containsEntry("paid_minor", 1990L);
        assertThat(order(no).get("succeeded_at")).isNotNull();
    }

    @Test
    @DisplayName("★★★ 关单后到账：支付单仍是 CLOSED、建一条 LATE_PAYMENT 待处置（R18）；重推不重复建")
    void latePaymentCreatesException() {
        String no = opened(1990);
        ledger.close(no, "支付超时");
        jdbc.update("UPDATE pay_order SET status='CLOSED' WHERE out_order_no=?", no);
        settle.settlePayment(paid(no, 1990));
        settle.settlePayment(paid(no, 1990));
        assertThat(order(no)).containsEntry("status", "CLOSED").containsEntry("paid_minor", 0L);
        assertThat(jdbc.queryForList("SELECT kind, status, amount_minor FROM pay_exception WHERE pay_order_id=?",
                order(no).get("id"))).singleElement().satisfies(e -> assertThat(e)
                .containsEntry("kind", "LATE_PAYMENT").containsEntry("status", "OPEN").containsEntry("amount_minor", 1990L));
    }

    @Test
    @DisplayName("★★★ 实收与应收不符：应收留在支付单上（R20），实收记在尝试上；支付单不进成功、建 AMOUNT_MISMATCH；回执报实收")
    void amountMismatchKeepsRequestedAmountOnPayOrder() {
        String no = opened(1990);
        settle.settlePayment(paid(no, 2000));
        assertThat(jdbc.queryForMap("SELECT amount_minor, channel_amount_minor, channel_currency FROM stl_payment"
                + " WHERE out_trade_no=?", no)).containsEntry("amount_minor", 2000L)
                .containsEntry("channel_amount_minor", 2000L).containsEntry("channel_currency", "CNY");
        assertThat(order(no)).containsEntry("status", "AWAITING_PAYMENT").containsEntry("amount_minor", 1990L)
                .containsEntry("paid_minor", 0L);
        assertThat(jdbc.queryForObject("SELECT kind FROM pay_exception WHERE pay_order_id=?", String.class,
                order(no).get("id"))).isEqualTo("AMOUNT_MISMATCH");
        assertThat(settle.paymentByOutTradeNo(no).amountMinor()).as("交易域按回执判「实收不符」，回执要报实收").isEqualTo(2000L);
    }
}
