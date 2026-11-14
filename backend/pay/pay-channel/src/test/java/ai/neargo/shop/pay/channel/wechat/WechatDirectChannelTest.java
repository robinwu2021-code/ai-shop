package ai.neargo.shop.pay.channel.wechat;

import static ai.neargo.shop.pay.channel.wechat.Rig.APP_ID;
import static ai.neargo.shop.pay.channel.wechat.Rig.MCH_ID;
import static ai.neargo.shop.pay.channel.wechat.Rig.NOTIFY;
import static ai.neargo.shop.pay.channel.wechat.Rig.OPENID;
import static ai.neargo.shop.pay.channel.wechat.Rig.FakeWechat.json;
import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.pay.channel.PayGateway;
import ai.neargo.shop.pay.channel.api.ChannelResult;
import ai.neargo.shop.pay.channel.api.InvocationMeta;
import ai.neargo.shop.pay.channel.api.Money;
import ai.neargo.shop.pay.channel.api.PayErrorCode;
import ai.neargo.shop.pay.channel.api.PayScene;
import ai.neargo.shop.pay.channel.api.SourceInfo;
import ai.neargo.shop.pay.channel.api.command.PayCommand;
import ai.neargo.shop.pay.channel.api.command.SplitCommand;
import ai.neargo.shop.pay.channel.wechat.direct.WechatDirectChannel;
import ai.neargo.shop.spi.pay.RefundQueryResult;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * 微信<b>直连商户号</b>处理器（TDD-支付服务-W2渠道层补完 S5 · AC6）。用例自 {@code WechatDirectPayGatewayTest} /
 * {@code WechatRefundReceiptFlowTest} 逐条搬来，断言不变；入口从「直调网关」换成「桥 → 管线 → 处理器 → 协议 → 记录型客户端」，
 * 因为 CN 行为不变说的是调用方从桥上拿到的东西不变。
 *
 * <p>守的三件事同旧网关：缺件不发；发的是直连的报文（混进收付通字段 = NO_AUTH）；补差与分账一律拒绝（直连不实现分账能力）。
 */
class WechatDirectChannelTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static Rig rig() {
        Rig r = new Rig();
        return r.with(r.direct(APP_ID, NOTIFY));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(Rig r) {
        return JSON.readValue(r.peer.last().body(), Map.class);
    }

    // ---------------------------------------------------------------- 下单

    @Test
    @DisplayName("JSAPI 下单报文：直连的字段齐，且不带任何收付通字段")
    void 下单报文是直连的() {
        Rig r = rig();
        r.peer.script = q -> json(200, "{\"prepay_id\":\"wx04123456\"}");

        var res = r.bridge.prepay("WECHAT", Rig.prepay("P20260904001", 1L, OPENID));

        assertThat(res.success()).isTrue();
        assertThat(r.peer.last().method()).isEqualTo("POST");
        assertThat(r.peer.last().pathAndQuery()).isEqualTo("/v3/pay/transactions/jsapi");
        Map<String, Object> body = body(r);
        assertThat(body).containsEntry("appid", APP_ID).containsEntry("mchid", MCH_ID)
                .containsEntry("out_trade_no", "P20260904001").containsEntry("notify_url", NOTIFY)
                .containsEntry("amount", Map.of("total", 1, "currency", "CNY"))
                .containsEntry("payer", Map.of("openid", OPENID));
        assertThat(body).doesNotContainKey("sub_mchid").doesNotContainKey("combine_out_trade_no");
        // 请求是签过名的（APIv3 Authorization 头）
        assertThat(r.peer.last().headers().get("Authorization")).startsWith("WECHATPAY2-SHA256-RSA2048 ");
    }

    @Test
    @DisplayName("下单成功回给端上的是小程序六件套，package 带 prepay_id= 前缀；通道交易号如实为空")
    void 端上六件套() {
        Rig r = rig();
        r.peer.script = q -> json(200, "{\"prepay_id\":\"wx04123456\"}");

        var res = r.bridge.prepay("WECHAT", Rig.prepay("P1", 100L, OPENID));

        assertThat(res.params()).containsKeys("appId", "timeStamp", "nonceStr", "package", "signType", "paySign");
        assertThat(res.params().get("package")).isEqualTo("prepay_id=wx04123456");
        assertThat(res.params()).containsEntry("signType", "RSA");
        assertThat(res.params().get("paySign")).isNotBlank();
        assertThat(res.tradeNo()).isNull();
    }

    @Test
    @DisplayName("没有 openid 就不下单 —— 而且通道一次都不能被调到")
    void 没有openid不发通道() {
        Rig r = rig();

        var res = r.bridge.prepay("WECHAT", Rig.prepay("P1", 100L, null));

        assertThat(res.success()).isFalse();
        assertThat(res.message()).contains("openid");
        assertThat(r.peer.requests).isEmpty();
    }

    @Test
    @DisplayName("回调地址没配就不下单 —— 发出去也收不到通知，那笔单会停在 PENDING")
    void 没配回调地址不发通道() {
        Rig r = new Rig();
        r.with(r.direct(APP_ID, ""));

        var res = r.bridge.prepay("WECHAT", Rig.prepay("P1", 100L, OPENID));

        assertThat(res.success()).isFalse();
        assertThat(res.message()).contains("notify-url");
        assertThat(r.peer.requests).isEmpty();
    }

    @Test
    @DisplayName("不是 JSAPI 的场景显式拒绝，不静默按 JSAPI 发（直连只声明了 ONLINE_SDK）")
    void 非JSAPI显式拒绝() {
        Rig r = rig();
        var ctx = r.bridgeContext();

        ChannelResult res = r.gateway.execute(ctx, new PayCommand(meta(), PayScene.ONLINE_QR, new Money(100, "CNY"),
                "订单", "P1", null, OPENID, null));

        assertThat(res).isInstanceOfSatisfying(ChannelResult.Failed.class,
                f -> assertThat(f.code()).isEqualTo(PayErrorCode.CHANNEL_UNSUPPORTED));
        assertThat(r.peer.requests).isEmpty();
    }

    @Test
    @DisplayName("通道没返回 prepay_id 算失败，不给端上一组付不了的参数")
    void 缺prepayId算失败() {
        Rig r = rig();
        r.peer.script = q -> json(200, "{\"code\":\"PARAM_ERROR\"}");

        var res = r.bridge.prepay("WECHAT", Rig.prepay("P1", 100L, OPENID));

        assertThat(res.success()).isFalse();
        assertThat(res.params()).isEmpty();
        // 渠道收没收下说不清：不是明确拒绝
        assertThat(res.definitiveRejection()).isFalse();
    }

    @Test
    @DisplayName("★★ AC4 调用记录写的是真实方法与路径，不再是占位 -")
    void callLogCarriesRealHttpCoordinates() {
        Rig r = rig();
        r.peer.script = q -> json(200, "{\"prepay_id\":\"wx1\"}");

        r.bridge.prepay("WECHAT", Rig.prepay("P1", 100L, OPENID));

        assertThat(r.calls.rows).singleElement().satisfies(row -> {
            assertThat(row.getHttpMethod()).isEqualTo("POST");
            assertThat(row.getUrlPath()).isEqualTo("/v3/pay/transactions/jsapi");
            assertThat(row.getHttpStatus()).isEqualTo(200);
            assertThat(row.getRequestMasked()).contains("P1").doesNotContain("signature=");
        });
        assertThat(r.raws).hasSize(2);
    }

    @Test
    @DisplayName("主域名连不上切备域名重发一次（微信要求商户实现主备切换）")
    void failsOverToBackupHost() {
        Rig r = rig();
        r.peer.primaryDown = true;
        r.peer.script = q -> json(200, "{\"prepay_id\":\"wx1\"}");

        var res = r.bridge.prepay("WECHAT", Rig.prepay("P1", 100L, OPENID));

        assertThat(res.success()).isTrue();
        assertThat(r.peer.requests).extracting(q -> q.host())
                .containsExactly(WechatV3Protocol.HOST, WechatV3Protocol.HOST_BACKUP);
    }

    // ---------------------------------------------------------------- 查单

    @Test
    @DisplayName("查单走 GET，且 ?mchid= 在路径里（它要参与签名）；保留渠道实际币种")
    void 查单走GET带mchid() {
        Rig r = rig();
        r.peer.script = q -> json(200, "{\"out_trade_no\":\"P1\",\"trade_state\":\"SUCCESS\",\"transaction_id\":\"4200001\","
                + "\"amount\":{\"total\":1,\"currency\":\"USD\"}}");

        var res = r.bridge.query("WECHAT", "P1");

        assertThat(r.peer.last().method()).isEqualTo("GET");
        assertThat(r.peer.last().pathAndQuery()).isEqualTo("/v3/pay/transactions/out-trade-no/P1?mchid=" + MCH_ID);
        assertThat(res.paid()).isTrue();
        assertThat(res.tradeNo()).isEqualTo("4200001");
        assertThat(res.amountMinor()).isEqualTo(1L);
        assertThat(res.currency()).isEqualTo("USD");
    }

    @Test
    @DisplayName("USERPAYING 不是已支付 —— 当成已付会给一笔没付的单发货")
    void 用户支付中不算已付() {
        Rig r = rig();
        r.peer.script = q -> json(200, "{\"trade_state\":\"USERPAYING\"}");

        var res = r.bridge.query("WECHAT", "P1");

        assertThat(res.ok()).isTrue();
        assertThat(res.paid()).isFalse();
        assertThat(res.found()).isTrue();
    }

    @Test
    @DisplayName("查询失败不是「通道没有这笔」—— 后者会被拿去关单")
    void 查询失败不等于没有这笔() {
        Rig r = rig();
        r.peer.script = q -> json(502, "{\"code\":\"SYSTEM_ERROR\",\"message\":\"网关错误\"}");

        var res = r.bridge.query("WECHAT", "P1");

        assertThat(res.ok()).isFalse();
        assertThat(res.ok() && !res.found()).isFalse();
    }

    @Test
    @DisplayName("★★★ ORDER_NOT_EXIST 才是「通道没有这笔」，可以安全关单（真微信原话：HTTP 404 + 带下划线）")
    void 订单不存在可关单() {
        Rig r = rig();
        r.peer.script = q -> json(404, "{\"code\":\"ORDER_NOT_EXIST\",\"message\":\"订单不存在\"}");

        var res = r.bridge.query("WECHAT", "P1");

        assertThat(res.ok()).isTrue();
        assertThat(res.found()).as("没认出「订单不存在」—— 这种单对账永远关不掉").isFalse();
        // 不重试：渠道已经明确说了没有
        assertThat(r.peer.requests).hasSize(1);
    }

    @Test
    @DisplayName("★★★ 别的 404/401 不算「没有这笔」—— 混为一谈会把已付的单关掉")
    void 其他失败不当成不存在() {
        Rig r = rig();
        r.peer.script = q -> json(401, "{\"code\":\"SIGN_ERROR\",\"message\":\"签名错误\"}");

        assertThat(r.bridge.query("WECHAT", "P1").ok()).isFalse();
    }

    @Test
    @DisplayName("应答验签不过等于没收到 —— 不能把内容当真（查不通，不是已付）")
    void badResponseSignatureIsNotTrusted() {
        Rig r = rig();
        r.peer.badSignature = true;
        r.peer.script = q -> json(200, "{\"trade_state\":\"SUCCESS\",\"transaction_id\":\"FAKE\"}");

        var res = r.bridge.query("WECHAT", "P1");

        assertThat(res.ok()).isFalse();
        assertThat(res.paid()).isFalse();
    }

    // ---------------------------------------------------------------- 退款与查退款

    @Test
    @DisplayName("退款走直连路径，且优先用通道交易号（我方单号重试时会带后缀）")
    void 退款报文() {
        Rig r = rig();
        r.peer.script = q -> json(200, "{\"refund_id\":\"50000000\",\"status\":\"PROCESSING\"}");

        var res = r.bridge.refund("WECHAT", new PayGateway.TxContext(null, "4200001", "P1", 100L), 30L, "R1", "七天无理由");

        assertThat(res.success()).isTrue();
        assertThat(res.providerNo()).isEqualTo("50000000");
        assertThat(r.peer.last().pathAndQuery()).isEqualTo("/v3/refund/domestic/refunds");
        assertThat(body(r)).containsEntry("transaction_id", "4200001").containsEntry("out_refund_no", "R1")
                .containsEntry("amount", Map.of("refund", 30, "total", 100, "currency", "CNY"))
                .doesNotContainKey("sub_mchid");
    }

    @Test
    @DisplayName("★★ F13 · R32 退款回调地址按 URI 推导，不是字符串拼接")
    void refundNotifyUrlIsDerivedNotConcatenated() {
        for (String[] c : new String[][] {
                {"https://h.example/pay/callback/WECHAT", "https://h.example/pay/callback/WECHAT/refund"},
                {"https://h.example/pay/callback/WECHAT/", "https://h.example/pay/callback/WECHAT/refund"},
                {"https://h.example/pay/callback/WECHAT?env=prod", "https://h.example/pay/callback/WECHAT/refund?env=prod"}}) {
            Rig r = new Rig();
            r.with(r.direct(APP_ID, c[0]));
            r.peer.script = q -> json(200, "{\"refund_id\":\"50000000\"}");
            r.bridge.refund("WECHAT", new PayGateway.TxContext(null, "4200001", "P1", 100L), 30L, "R1", "七天无理由");
            assertThat(body(r)).as(c[0]).containsEntry("notify_url", c[1]);
        }
        assertThat(WechatDirectChannel.refundUrlOf(null)).isNull();
    }

    @Test
    @DisplayName("受理回执里没有退款单号 = 不明，不是拒绝的证据（只查不重发）")
    void acceptedResponseWithoutProviderIsUnknownNotARejectionProof() {
        Rig r = rig();
        r.peer.script = q -> json(200, "{\"status\":\"PROCESSING\"}");

        var res = r.bridge.refund("WECHAT", new PayGateway.TxContext(null, "T", "O", 100), 30, "R", "refund");

        assertThat(r.peer.requests).hasSize(1);
        assertThat(res.success()).isFalse();
        assertThat(res.retryable()).isTrue();
        assertThat(res.definitiveRejection()).isFalse();
    }

    @Test
    @DisplayName("查退款是另一套单据，走退款单号的接口")
    void 查退款用退款接口() {
        Rig r = rig();
        r.peer.script = q -> json(200, "{\"status\":\"SUCCESS\",\"refund_id\":\"50000\",\"amount\":{\"refund\":1}}");

        var res = r.bridge.queryRefund("WECHAT", "R1");

        assertThat(r.peer.last().method()).isEqualTo("GET");
        assertThat(r.peer.last().pathAndQuery()).isEqualTo("/v3/refund/domestic/refunds/R1");
        assertThat(res.paid()).isTrue();
        assertThat(res.tradeNo()).isEqualTo("50000");
    }

    @Test
    @DisplayName("退款未找到不能证明从未受理，不自动关单")
    void 退款单不存在保持未知() {
        Rig r = rig();
        r.peer.script = q -> json(404, "{\"code\":\"RESOURCE_NOT_EXISTS\",\"message\":\"退款单不存在\"}");

        var res = r.bridge.queryRefund("WECHAT", "R1");

        assertThat(res.ok()).isFalse();
        assertThat(res.found()).isFalse();
        assertThat(r.bridge.queryRefundOutcome("WECHAT", "R1").status()).isEqualTo(RefundQueryResult.Status.UNKNOWN);
    }

    @Test
    @DisplayName("退款回执五态：CLOSED 是终态并带凭据；ABNORMAL 转人工；PROCESSING；没见过的状态 = 未知；币种与单号不编造")
    void refundOutcomeFiveStates() {
        Rig r = rig();
        r.peer.script = q -> json(200, "{\"status\":\"CLOSED\",\"refund_id\":\"provider\",\"out_refund_no\":\"RF\","
                + "\"amount\":{\"refund\":125,\"currency\":\"CNY\"}}");
        RefundQueryResult closed = r.bridge.queryRefundOutcome("WECHAT", "RF");
        assertThat(closed.status()).isEqualTo(RefundQueryResult.Status.TERMINAL_FAILED);
        assertThat(closed.providerNo()).isEqualTo("provider");
        assertThat(closed.amountMinor()).isEqualTo(125);
        assertThat(closed.currency()).isEqualTo("CNY");
        assertThat(closed.rawStatus()).isEqualTo("CLOSED");
        assertThat(closed.outRefundNo()).isEqualTo("RF");

        r.peer.script = q -> json(200, "{\"status\":\"ABNORMAL\"}");
        assertThat(r.bridge.queryRefundOutcome("WECHAT", "RF").status()).isEqualTo(RefundQueryResult.Status.MANUAL_REQUIRED);
        r.peer.script = q -> json(200, "{\"status\":\"PROCESSING\"}");
        RefundQueryResult processing = r.bridge.queryRefundOutcome("WECHAT", "RF");
        assertThat(processing.status()).isEqualTo(RefundQueryResult.Status.PROCESSING);
        assertThat(processing.rawStatus()).isEqualTo("PROCESSING");
        r.peer.script = q -> json(200, "{\"status\":\"FUTURE_STATUS\"}");
        assertThat(r.bridge.queryRefundOutcome("WECHAT", "RF").status()).isEqualTo(RefundQueryResult.Status.UNKNOWN);

        r.peer.script = q -> json(200, "{\"status\":\"SUCCESS\",\"refund_id\":\"provider-usd\","
                + "\"amount\":{\"refund\":125,\"currency\":\"USD\"}}");
        RefundQueryResult usd = r.bridge.queryRefundOutcome("WECHAT", "RF");
        assertThat(usd.status()).isEqualTo(RefundQueryResult.Status.SUCCEEDED);
        assertThat(usd.amountMinor()).isEqualTo(125);
        assertThat(usd.currency()).isEqualTo("USD");
        r.peer.script = q -> json(200, "{\"status\":\"SUCCESS\",\"amount\":{\"refund\":125}}");
        RefundQueryResult bare = r.bridge.queryRefundOutcome("WECHAT", "RF");
        assertThat(bare.providerNo()).isNull();
        assertThat(bare.currency()).isNull();

        // 渠道回的是别人的退款单 → 不认
        r.peer.script = q -> json(200, "{\"status\":\"SUCCESS\",\"out_refund_no\":\"OTHER\",\"refund_id\":\"p\","
                + "\"amount\":{\"refund\":1,\"currency\":\"CNY\"}}");
        assertThat(r.bridge.queryRefundOutcome("WECHAT", "RF").status()).isEqualTo(RefundQueryResult.Status.UNKNOWN);
    }

    // ---------------------------------------------------------------- 补差与分账

    @Test
    @DisplayName("补差与分账一律拒绝，且不可重试 —— 直连是归集路径，不实现分账能力，一个字节都不发")
    void 补差分账一律拒绝() {
        Rig r = rig();
        var ctx = r.bridgeContext();
        for (SplitCommand.Kind kind : SplitCommand.Kind.values()) {
            ChannelResult res = r.gateway.execute(ctx, new SplitCommand(meta(), kind, null, "4200001", "P1",
                    new Money(100, "CNY"), new Money(3, "CNY"), "S-" + kind, "积分补差"));
            assertThat(res).isInstanceOfSatisfying(ChannelResult.Failed.class, f -> {
                assertThat(f.code()).isEqualTo(PayErrorCode.CHANNEL_UNSUPPORTED);
                assertThat(f.retryable()).isFalse();
            });
        }
        assertThat(r.peer.requests).isEmpty();
    }

    private static InvocationMeta meta() {
        return new InvocationMeta(null, null, null, null, null, SourceInfo.system("T", "B"));
    }
}
