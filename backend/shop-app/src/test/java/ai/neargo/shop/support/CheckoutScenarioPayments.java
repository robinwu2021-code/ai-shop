package ai.neargo.shop.support;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.pay.channel.TestPayGateway;
import ai.neargo.shop.spi.settle.SettlePort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Receives a WECHAT checkout through the real ledger. The stateful TEST gateway is only
 * the upstream bank simulator here; the persisted channel remains the business-supported
 * WECHAT identity. No production gateway routing or accepted-channel list is changed.
 */
public final class CheckoutScenarioPayments {
    public static final String CHANNEL = "WECHAT";

    private CheckoutScenarioPayments() { }

    public static String receive(JdbcTemplate jdbc, SettlePort settlement, TestPayGateway channel, String order) {
        var source = jdbc.queryForMap("SELECT user_no,pay_amount FROM ord_order WHERE order_no=?", order);
        long amount = ((Number) source.get("pay_amount")).longValue();
        String out = settlement.openPayment(new SettlePort.PaymentOpen(order, (String) source.get("user_no"),
                null, CHANNEL, amount, "Checkout scenario"));
        channel.placeOrder(out, amount);
        channel.markPaid(out);
        var confirmed = channel.query(out);
        assertThat(confirmed.paid()).isTrue();
        assertThat(confirmed.amountMinor()).isEqualTo(amount);
        assertThat(settlement.settlePayment(new SettlePort.PaymentSettled(out, CHANNEL,
                confirmed.tradeNo(), confirmed.amountMinor(), System.currentTimeMillis()))).isEqualTo(order);
        var receipt = settlement.successPayment(order, CHANNEL, confirmed.tradeNo());
        assertThat(receipt).isNotNull();
        assertThat(receipt.userNo()).isEqualTo(source.get("user_no"));
        assertThat(receipt.payChannel()).isEqualTo(CHANNEL);
        assertThat(receipt.currency()).isEqualTo(confirmed.currency());
        assertThat(receipt.amountMinor()).isEqualTo(amount);
        return receipt.tradeNo();
    }
}
