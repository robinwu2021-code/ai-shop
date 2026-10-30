package ai.neargo.shop.pay.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.pay.channel.alipay.AlipayChannel;
import ai.neargo.shop.pay.channel.alipay.AlipayClient;
import ai.neargo.shop.pay.channel.api.ChannelAccountContext;
import ai.neargo.shop.pay.channel.api.ChannelResult;
import ai.neargo.shop.pay.channel.api.InvocationMeta;
import ai.neargo.shop.pay.channel.api.SourceInfo;
import ai.neargo.shop.pay.channel.api.command.QueryCommand;
import ai.neargo.shop.pay.channel.invoke.Invocation;
import ai.neargo.shop.pay.channel.wechat.WechatV3Client;
import ai.neargo.shop.pay.channel.wechat.direct.WechatDirectChannel;
import ai.neargo.shop.pay.channel.wechat.ecommerce.WechatEcommerceChannel;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Receipt identity checks run against the current channel handlers, never deleted legacy gateways. */
class PaymentOriginalNumberGatewayTest {
    private static final QueryCommand QUERY = new QueryCommand(
            new InvocationMeta(null, null, null, null, null, SourceInfo.system("TEST", "ORIGINAL")), "ORIGINAL");

    @Test
    void wechatModesRejectForeignAndMalformedSuccessfulReceipts() {
        var client = mock(WechatV3Client.class);
        var invocation = mock(Invocation.class);
        var account = mock(ChannelAccountContext.class);
        when(invocation.account()).thenReturn(account);
        when(account.subMerchantId()).thenReturn("SUB");
        var direct = new WechatDirectChannel(client, null, "MCH", "APP", "callback", null);
        var ecommerce = new WechatEcommerceChannel(client, "SP", no -> Optional.of("SUB"), ch -> false);
        for (Map<String, Object> response : invalidWechatReceipts()) {
            when(client.get(any(), anyString())).thenReturn(response);
            assertThat(direct.query(invocation, QUERY)).isInstanceOf(ChannelResult.Unknown.class);
            assertThat(ecommerce.query(invocation, QUERY)).isInstanceOf(ChannelResult.Unknown.class);
        }
        when(client.get(any(), anyString())).thenReturn(wechat("ORIGINAL", 123, "USD"));
        for (var result : List.of(direct.query(invocation, QUERY), ecommerce.query(invocation, QUERY))) {
            assertThat(result).isInstanceOfSatisfying(ChannelResult.Succeeded.class, success -> {
                assertThat(success.channelTradeNo()).isEqualTo("TX");
                assertThat(success.channelAmount().minor()).isEqualTo(123);
                assertThat(success.channelAmount().currency()).isEqualTo("USD");
            });
        }
    }

    @Test
    void alipayRejectsForeignMissingAndFractionalReceipts() {
        var client = mock(AlipayClient.class);
        var channel = new AlipayChannel(client, "ACCOUNT", ch -> false);
        var invocation = mock(Invocation.class);
        var missingAmount = new HashMap<>(alipay("ORIGINAL", "1.23"));
        missingAmount.remove("total_amount");
        var missingTrade = new HashMap<>(alipay("ORIGINAL", "1.23"));
        missingTrade.remove("trade_no");
        for (var response : List.of(alipay("FOREIGN", "1.23"), alipay("ORIGINAL", "1.234"),
                alipay("ORIGINAL", "-1.00"), missingAmount, missingTrade)) {
            when(client.call(any(), anyString(), anyMap())).thenReturn(response);
            assertThat(channel.query(invocation, QUERY)).isInstanceOf(ChannelResult.Unknown.class);
        }
        when(client.call(any(), anyString(), anyMap())).thenReturn(alipay("ORIGINAL", "1.23"));
        assertThat(channel.query(invocation, QUERY)).isInstanceOfSatisfying(ChannelResult.Succeeded.class,
                success -> assertThat(success.channelAmount().minor()).isEqualTo(123));
    }

    @Test
    void rejectionAndUnsentProofRemainDifferentFromUnknownAcceptance() {
        assertThat(PayGateway.PrepayResult.unknown("timeout").disposition())
                .isEqualTo(PayGateway.PrepayDisposition.UNKNOWN);
        assertThat(PayGateway.PrepayResult.unknown("timeout").definitiveRejection()).isFalse();
        assertThat(new PayGateway.PrepayResult(false, Map.of(), null, "unproven").definitiveRejection()).isFalse();
        assertThat(new PayGateway.PrepayResult(false, Map.of(), null, "rejected", true).disposition())
                .isEqualTo(PayGateway.PrepayDisposition.REJECTED);
        assertThat(PayGateway.PrepayResult.fail("rejected").disposition())
                .isEqualTo(PayGateway.PrepayDisposition.REJECTED);
        assertThat(PayGateway.PrepayResult.notSent("local precondition").disposition())
                .isEqualTo(PayGateway.PrepayDisposition.NOT_SENT);
        assertThat(PayGateway.PrepayResult.notSent("local precondition").definitiveRejection()).isTrue();
    }

    private static List<Map<String, Object>> invalidWechatReceipts() {
        var missingTrade = new HashMap<>(wechat("ORIGINAL", 123, "CNY"));
        missingTrade.remove("transaction_id");
        return List.of(wechat("FOREIGN", 123, "CNY"), wechat("ORIGINAL", "123", "CNY"),
                wechat("ORIGINAL", new BigDecimal("1.5"), "CNY"),
                wechat("ORIGINAL", new BigDecimal("9223372036854775808"), "CNY"),
                wechat("ORIGINAL", -1, "CNY"), wechat("ORIGINAL", 123, ""), missingTrade,
                Map.of("out_trade_no", "ORIGINAL", "trade_state", "SUCCESS", "transaction_id", "TX"));
    }

    private static Map<String, Object> wechat(String original, Object amount, String currency) {
        return Map.of("out_trade_no", original, "trade_state", "SUCCESS", "transaction_id", "TX",
                "amount", Map.of("total", amount, "currency", currency));
    }

    private static Map<String, Object> alipay(String original, String amount) {
        return Map.of("code", "10000", "out_trade_no", original, "trade_status", "TRADE_SUCCESS",
                "trade_no", "TX", "total_amount", amount);
    }
}
