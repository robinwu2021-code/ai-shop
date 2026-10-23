package ai.neargo.shop.pay.channel;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Actual provider adapters must prove the queried original identity and exact money before
 * reporting paid.
 */
class PaymentOriginalNumberGatewayTest {
    @Test
    void directWechatRejectsSuccessForAnotherOriginalNumber() {
        var client = new ReplyClient();
        client.reply = wechat("FOREIGN", 123, "CNY");
        assertThat(direct(client).query("ORIGINAL").ok()).isFalse();
    }

    @Test
    void ecommerceWechatRejectsSuccessForAnotherOriginalNumber() {
        var client = new ReplyClient();
        client.reply = wechat("FOREIGN", 123, "CNY");
        assertThat(ecommerce(client).query("ORIGINAL").ok()).isFalse();
    }

    @Test
    void alipayRejectsSuccessForAnotherOriginalNumber() {
        var client = new ReplyClient();
        client.reply = alipay("FOREIGN", "1.23");
        assertThat(alipay(client).query("ORIGINAL").ok()).isFalse();
    }

    @Test
    void malformedOrMissingReceiptFactsNeverBecomeZeroOrTruncatedMoney() {
        var client = new ReplyClient();
        for (var gateway : List.of(direct(client), ecommerce(client))) {
            for (Object amount :
                    List.of(
                            "123",
                            new BigDecimal("1.5"),
                            new BigDecimal("9223372036854775808"),
                            -1)) {
                client.reply = wechat("ORIGINAL", amount, "CNY");
                assertThat(gateway.query("ORIGINAL").ok())
                        .as("invalid exact minor units")
                        .isFalse();
            }
            client.reply =
                    Map.of(
                            "out_trade_no",
                            "ORIGINAL",
                            "trade_state",
                            "SUCCESS",
                            "transaction_id",
                            "TX");
            assertThat(gateway.query("ORIGINAL").ok()).isFalse();
            client.reply = wechat("ORIGINAL", 123, "");
            assertThat(gateway.query("ORIGINAL").ok()).isFalse();
            client.reply = new HashMap<>(wechat("ORIGINAL", 123, "CNY"));
            client.reply.remove("transaction_id");
            assertThat(gateway.query("ORIGINAL").ok()).isFalse();
        }
        client.reply = new HashMap<>(alipay("ORIGINAL", "1.23"));
        client.reply.remove("total_amount");
        assertThat(alipay(client).query("ORIGINAL").ok()).isFalse();
        client.reply = new HashMap<>(alipay("ORIGINAL", "1.23"));
        client.reply.remove("trade_no");
        assertThat(alipay(client).query("ORIGINAL").ok()).isFalse();
        client.reply = alipay("ORIGINAL", "1.234");
        assertThat(alipay(client).query("ORIGINAL").ok()).isFalse();
    }

    @Test
    void localPreconditionAndUnimplementedDispatchProveNotSentWithoutCallingThirdParty() {
        var client = new ReplyClient();
        var request =
                new PayGateway.PrepayCommand(
                        "ORIGINAL", 123, "CNY", "original", "JSAPI", null, null);
        assertThat(direct(client).prepay(request).disposition())
                .isEqualTo(PayGateway.PrepayDisposition.NOT_SENT);
        assertThat(ecommerce(client).prepay(request).disposition())
                .isEqualTo(PayGateway.PrepayDisposition.NOT_SENT);
        assertThat(alipay(client).prepay(request).disposition())
                .isEqualTo(PayGateway.PrepayDisposition.NOT_SENT);
        assertThat(client.calls).isZero();
    }

    @Test
    void validOriginalReceiptsKeepActualCurrencyAndMoney() {
        var client = new ReplyClient();
        client.reply = wechat("ORIGINAL", 123, "USD");
        for (var gateway : List.of(direct(client), ecommerce(client))) {
            var receipt = gateway.query("ORIGINAL");
            assertThat(receipt.paid()).isTrue();
            assertThat(receipt.amountMinor()).isEqualTo(123);
            assertThat(receipt.currency()).isEqualTo("USD");
            assertThat(receipt.tradeNo()).isEqualTo("TX");
        }
        client.reply = alipay("ORIGINAL", "1.23");
        assertThat(alipay(client).query("ORIGINAL").amountMinor()).isEqualTo(123);
        assertThat(alipay(client).query("ORIGINAL").currency()).isEqualTo("CNY");
    }

    private static WechatDirectPayGateway direct(ReplyClient client) {
        return new WechatDirectPayGateway(
                client,
                null,
                new WechatDirectPayGatewayTest.SilentRecorder(),
                null,
                "isolated-mch",
                "isolated-app",
                "http://127.0.0.1/callback");
    }

    private static WechatPayGateway ecommerce(ReplyClient client) {
        return new WechatPayGateway(client, null, new WechatDirectPayGatewayTest.SilentRecorder());
    }

    private static AlipayPayGateway alipay(ReplyClient client) {
        return new AlipayPayGateway(
                client, null, new WechatDirectPayGatewayTest.SilentRecorder(), "isolated-account");
    }

    private static Map<String, Object> wechat(String number, Object amount, String currency) {
        return Map.of(
                "out_trade_no",
                number,
                "trade_state",
                "SUCCESS",
                "transaction_id",
                "TX",
                "amount",
                Map.of("total", amount, "currency", currency));
    }

    private static Map<String, Object> alipay(String number, String amount) {
        return Map.of(
                "out_trade_no",
                number,
                "trade_status",
                "TRADE_SUCCESS",
                "trade_no",
                "TX",
                "total_amount",
                amount);
    }

    private static final class ReplyClient implements ChannelClient {
        Map<String, Object> reply = Map.of();
        int calls;

        @Override
        public Map<String, Object> post(String api, Map<String, Object> body) {
            calls++;
            return reply;
        }

        @Override
        public Map<String, Object> get(String api) {
            calls++;
            return reply;
        }
    }
}
