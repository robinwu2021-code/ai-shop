package ai.neargo.shop.pay.channel.wechat;

import static ai.neargo.shop.pay.channel.wechat.Rig.OPENID;
import static ai.neargo.shop.pay.channel.wechat.Rig.FakeWechat.json;
import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.pay.channel.PayGateway;
import ai.neargo.shop.pay.channel.api.ChannelResult;
import ai.neargo.shop.pay.channel.api.InvocationMeta;
import ai.neargo.shop.pay.channel.api.Money;
import ai.neargo.shop.pay.channel.api.PayErrorCode;
import ai.neargo.shop.pay.channel.api.SourceInfo;
import ai.neargo.shop.pay.channel.api.command.SplitCommand;
import ai.neargo.shop.pay.channel.wechat.ecommerce.WechatEcommerceChannel;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * 微信<b>电商收付通</b>（服务商）处理器（TDD-支付服务-W2渠道层补完 S5 · AC6）。查单 / 查退款用例自
 * {@code WechatPayGatewayQueryTest} 搬来；补差与分账的报文自 {@code WechatPayGateway} 的四个 build 方法搬来，
 * 能力位（{@code supports_subsidy}）自 {@code AbstractPayGateway} 搬来。
 */
class WechatEcommerceChannelTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static Rig rig(Optional<String> sub, boolean subsidyAllowed) {
        Rig r = new Rig();
        return r.with(new WechatEcommerceChannel(r.client, "SP-1", no -> sub, ch -> subsidyAllowed));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(Rig r) {
        return JSON.readValue(r.peer.last().body(), Map.class);
    }

    @Test
    @DisplayName("★★★ F12 查单走服务商路径，带 sp_mchid 与这笔交易的 sub_mchid")
    void queryUsesPartnerPath() {
        Rig r = rig(Optional.of("SUB-9"), true);
        r.peer.script = q -> json(200, "{\"trade_state\":\"SUCCESS\",\"transaction_id\":\"4200001\","
                + "\"amount\":{\"total\":100,\"currency\":\"CNY\"}}");

        var res = r.bridge.query("WECHAT", "ORD-1");

        assertThat(r.peer.last().method()).isEqualTo("GET");
        assertThat(r.peer.last().pathAndQuery())
                .isEqualTo("/v3/pay/partner/transactions/out-trade-no/ORD-1?sp_mchid=SP-1&sub_mchid=SUB-9");
        assertThat(res.paid()).isTrue();
    }

    @Test
    @DisplayName("★★ F12 不知道子商户号就不查 —— 按「查询失败」处理，不能当成「通道没有这笔」去关单；一个字节都不发")
    void unknownSubMerchantIsQueryFailure() {
        Rig r = rig(Optional.empty(), true);

        var res = r.bridge.query("WECHAT", "ORD-1");

        assertThat(res.ok()).isFalse();
        assertThat(r.peer.requests).isEmpty();
    }

    @Test
    @DisplayName("★★★ F12 查退款走收付通退款接口；处理中不是成功")
    void queryRefundUsesEcommerceRefundPath() {
        Rig r = rig(Optional.of("SUB-9"), true);
        r.peer.script = q -> json(200, "{\"status\":\"SUCCESS\",\"refund_id\":\"50000001\","
                + "\"amount\":{\"refund\":30,\"currency\":\"CNY\"}}");

        var res = r.bridge.queryRefund("WECHAT", "ORD-1-R1");

        assertThat(r.peer.last().pathAndQuery()).isEqualTo("/v3/ecommerce/refunds/out-refund-no/ORD-1-R1?sub_mchid=SUB-9");
        assertThat(res.ok()).isTrue();
        assertThat(res.found()).isTrue();
        assertThat(res.paid()).isTrue();
        assertThat(res.amountMinor()).isEqualTo(30);

        r.peer.script = q -> json(200, "{\"status\":\"PROCESSING\"}");
        var pending = r.bridge.queryRefund("WECHAT", "ORD-1-R1");
        assertThat(pending.found()).isTrue();
        assertThat(pending.paid()).isFalse();
    }

    @Test
    @DisplayName("收付通下单（合单 JSAPI）尚未接：明确失败、不发渠道 —— 与旧网关同口径")
    void prepayNotImplementedYet() {
        Rig r = rig(Optional.of("SUB-9"), true);

        var res = r.bridge.prepay("WECHAT", Rig.prepay("P1", 100L, OPENID));

        assertThat(res.success()).isFalse();
        assertThat(res.message()).contains("尚未实现");
        assertThat(r.peer.requests).isEmpty();
    }

    @Test
    @DisplayName("退款走收付通退款接口，带 sub_mchid 与通道交易号")
    void refundBody() {
        Rig r = rig(Optional.of("SUB-9"), true);
        r.peer.script = q -> json(200, "{\"refund_id\":\"5000\"}");

        var res = r.bridge.refund("WECHAT", new PayGateway.TxContext("SUB-9", "4200001", "P1", 100L), 30L, "R1", "少发");

        assertThat(res.success()).isTrue();
        assertThat(r.peer.last().pathAndQuery()).isEqualTo("/v3/ecommerce/refunds/apply");
        assertThat(body(r)).containsEntry("sub_mchid", "SUB-9").containsEntry("transaction_id", "4200001")
                .containsEntry("out_refund_no", "R1")
                .containsEntry("amount", Map.of("refund", 30, "total", 100, "currency", "CNY"));
    }

    @Test
    @DisplayName("★★ 补差 / 分账 / 回退的报文与接口自旧网关逐字搬来；result=FAIL 不当成功")
    void splitBodies() {
        Rig r = rig(Optional.of("SUB-9"), true);
        var ctx = r.bridgeContext();

        r.peer.script = q -> json(200, "{\"subsidy_id\":\"SB1\",\"result\":\"SUCCESS\"}");
        ChannelResult subsidy = r.gateway.execute(ctx, split(SplitCommand.Kind.SUBSIDY));
        assertThat(r.peer.last().pathAndQuery()).isEqualTo("/v3/ecommerce/subsidies/create");
        assertThat(body(r)).containsEntry("sub_mchid", "SUB-9").containsEntry("transaction_id", "4200001")
                .containsEntry("amount", 3).containsEntry("out_subsidy_no", "REQ-SUBSIDY");
        assertThat(subsidy).isInstanceOfSatisfying(ChannelResult.Succeeded.class,
                s -> assertThat(s.channelTradeNo()).isEqualTo("SB1"));

        r.peer.script = q -> json(200, "{\"order_id\":\"PS1\"}");
        r.gateway.execute(ctx, split(SplitCommand.Kind.SPLIT));
        assertThat(r.peer.last().pathAndQuery()).isEqualTo("/v3/ecommerce/profitsharing/orders");
        assertThat(body(r)).containsEntry("out_order_no", "REQ-SPLIT").containsEntry("finish", false);

        r.peer.script = q -> json(200, "{\"return_id\":\"RT1\"}");
        r.gateway.execute(ctx, split(SplitCommand.Kind.SPLIT_REVERSE));
        assertThat(r.peer.last().pathAndQuery()).isEqualTo("/v3/ecommerce/profitsharing/returnorders");
        assertThat(body(r)).containsEntry("order_id", "4200001").containsEntry("out_return_no", "REQ-SPLIT_REVERSE");

        r.peer.script = q -> json(200, "{\"refund_id\":\"SR1\"}");
        r.gateway.execute(ctx, split(SplitCommand.Kind.SUBSIDY_RETURN));
        assertThat(r.peer.last().pathAndQuery()).isEqualTo("/v3/ecommerce/subsidies/return");

        r.peer.script = q -> json(200, "{\"subsidy_id\":\"SB2\",\"result\":\"FAIL\"}");
        assertThat(r.gateway.execute(ctx, split(SplitCommand.Kind.SUBSIDY)))
                .isInstanceOfSatisfying(ChannelResult.Failed.class,
                        f -> assertThat(f.code()).isEqualTo(PayErrorCode.CHANNEL_REJECTED));
    }

    @Test
    @DisplayName("★★ 能力位：sys_pay_channel.supports_subsidy=0 时补差绝不发出去，且不可重试")
    void subsidyDeniedByCapabilityBit() {
        Rig r = rig(Optional.of("SUB-9"), false);

        ChannelResult res = r.gateway.execute(r.bridgeContext(), split(SplitCommand.Kind.SUBSIDY));

        assertThat(res).isInstanceOfSatisfying(ChannelResult.Failed.class, f -> {
            assertThat(f.code()).isEqualTo(PayErrorCode.CHANNEL_REJECTED);
            assertThat(f.retryable()).isFalse();
            assertThat(f.channelMessage()).contains("supports_subsidy");
        });
        assertThat(r.peer.requests).isEmpty();
    }

    private static SplitCommand split(SplitCommand.Kind kind) {
        return new SplitCommand(new InvocationMeta(null, null, null, null, null, SourceInfo.system("T", "B")), kind,
                "SUB-9", "4200001", "P1", new Money(100, "CNY"), new Money(3, "CNY"), "REQ-" + kind, "积分补差");
    }
}
