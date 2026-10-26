package ai.neargo.shop.pay.channel;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.pay.channel.master.PayChannelMasterService;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * F12 · Review R14：电商收付通（服务商）模式的查单与查退款（TDD-支付服务-I1存量缺陷）。
 *
 * <p>此前查单走普通商户路径（不带 sp_mchid / sub_mchid），服务商模式下通道答不上；查退款没覆写，
 * 恒为「查不通」—— 收付通的退款回调回查永远失败、回 FAIL，退款一直停在处理中。
 */
class WechatPayGatewayQueryTest {

    static final class GetRecorder implements ChannelClient {
        String getApi;
        Map<String, Object> reply = Map.of();

        @Override
        public Map<String, Object> post(String api, Map<String, Object> body) {
            throw new AssertionError("查询只能是 GET：APIv3 待签串第一行是 HTTP 方法");
        }

        @Override
        public Map<String, Object> get(String api) {
            this.getApi = api;
            return reply;
        }
    }

    private static WechatPayGateway gateway(GetRecorder client, Optional<String> sub) {
        return new WechatPayGateway(client, Mockito.mock(PayChannelMasterService.class),
                Mockito.mock(ChannelMessageRecorder.class), "SP-1", no -> sub);
    }

    @Test
    @DisplayName("★★★ F12 查单走服务商路径，带 sp_mchid 与这笔交易的 sub_mchid")
    void queryUsesPartnerPath() {
        var c = new GetRecorder();
        c.reply = Map.of("trade_state", "SUCCESS", "transaction_id", "4200001",
                "amount", Map.of("total", 100, "currency", "CNY"));
        var r = gateway(c, Optional.of("SUB-9")).query("ORD-1");

        assertThat(c.getApi).isEqualTo("/v3/pay/partner/transactions/out-trade-no/ORD-1?sp_mchid=SP-1&sub_mchid=SUB-9");
        assertThat(r.paid()).isTrue();
    }

    @Test
    @DisplayName("★★ F12 不知道子商户号就不查 —— 按「查询失败」处理（对账留到下一轮），不能当成「通道没有这笔」去关单")
    void unknownSubMerchantIsQueryFailure() {
        var c = new GetRecorder();
        var r = gateway(c, Optional.empty()).query("ORD-1");
        assertThat(r.ok()).isFalse();
        assertThat(c.getApi).as("一个字节都不该发出去").isNull();
    }

    @Test
    @DisplayName("★★★ F12 查退款走收付通退款接口 —— 默认实现恒为查不通，退款回调回查永远失败")
    void queryRefundUsesEcommerceRefundPath() {
        var c = new GetRecorder();
        c.reply = Map.of("status", "SUCCESS", "refund_id", "50000001",
                "amount", Map.of("refund", 30, "currency", "CNY"));
        var r = gateway(c, Optional.of("SUB-9")).queryRefund("ORD-1-R1");

        assertThat(c.getApi).isEqualTo("/v3/ecommerce/refunds/out-refund-no/ORD-1-R1?sub_mchid=SUB-9");
        assertThat(r.ok()).isTrue();
        assertThat(r.found()).isTrue();
        assertThat(r.paid()).as("退款成功").isTrue();
        assertThat(r.amountMinor()).isEqualTo(30);

        c.reply = Map.of("status", "PROCESSING");
        var pending = gateway(c, Optional.of("SUB-9")).queryRefund("ORD-1-R1");
        assertThat(pending.found()).isTrue();
        assertThat(pending.paid()).as("处理中不是成功").isFalse();
    }
}
