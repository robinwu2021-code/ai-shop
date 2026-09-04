package ai.neargo.shop.pay.channel;

import ai.neargo.shop.pay.channel.entity.SysPayChannel;
import ai.neargo.shop.pay.channel.master.PayChannelMasterService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 微信<b>直连商户号</b>网关（小程序 JSAPI）。
 *
 * <p>这组用例守三件事，每一件失守的表现都<b>不是报错</b>：
 * <ol>
 *   <li><b>缺件不发</b>：没有 openid 还照发，通道返回 PARAM_ERROR，
 *       端上看到的是「点了没反应」；</li>
 *   <li><b>发的是直连的报文</b>：混进 {@code sub_mchid} 的话通道返回 NO_AUTH，
 *       而那个错误看起来像凭据配错；</li>
 *   <li><b>补差与分账一律拒绝</b>：返回成功能让结算链路立刻变绿，
 *       而绿的含义是「平台以为分账发出去了，通道那边什么都没发生」。</li>
 * </ol>
 */
class WechatDirectPayGatewayTest {

    private static final String APP_ID = "wxTESTAPPID";
    private static final String MCH_ID = "1900000109";
    private static final String NOTIFY = "https://example.test/callback/pay/channel/WECHAT";
    private static final String OPENID = "oABCDEFGHIJKLMN";

    // ---------------------------------------------------------------- 下单

    @Test
    @DisplayName("JSAPI 下单报文：直连的字段齐，且不带任何收付通字段")
    void 下单报文是直连的() throws Exception {
        RecordingClient client = new RecordingClient();
        client.reply = Map.of("prepay_id", "wx04123456");
        WechatDirectPayGateway g = gateway(client);

        var r = g.prepay(new PayGateway.PrepayCommand(
                "P20260904001", 1L, "CNY", "订单 O1", "JSAPI", null, OPENID));

        assertThat(r.success()).isTrue();
        assertThat(client.api).isEqualTo("/v3/pay/transactions/jsapi");
        assertThat(client.body).containsEntry("appid", APP_ID);
        assertThat(client.body).containsEntry("mchid", MCH_ID);
        assertThat(client.body).containsEntry("out_trade_no", "P20260904001");
        assertThat(client.body).containsEntry("notify_url", NOTIFY);
        assertThat(client.body).containsEntry("amount", Map.of("total", 1L, "currency", "CNY"));
        assertThat(client.body).containsEntry("payer", Map.of("openid", OPENID));

        // 收付通的字段一个都不能有 —— 混进去通道返回 NO_AUTH
        assertThat(client.body).doesNotContainKey("sub_mchid");
        assertThat(client.body).doesNotContainKey("combine_out_trade_no");
    }

    @Test
    @DisplayName("下单成功回给端上的是小程序六件套，package 带 prepay_id= 前缀")
    void 端上六件套() throws Exception {
        RecordingClient client = new RecordingClient();
        client.reply = Map.of("prepay_id", "wx04123456");

        var r = gateway(client).prepay(new PayGateway.PrepayCommand(
                "P1", 100L, "CNY", "订单 O1", "JSAPI", null, OPENID));

        assertThat(r.params()).containsKeys(
                "appId", "timeStamp", "nonceStr", "package", "signType", "paySign");
        assertThat(r.params().get("package")).startsWith("prepay_id=");
        assertThat(r.params()).containsEntry("signType", "RSA");
        assertThat(r.params().get("paySign")).isNotBlank();

        /*
         * **通道交易号如实为空。** 直连 JSAPI 的下单回执只有 prepay_id，
         * 拿它冒充 transaction_id 的话，对账每一轮都会报一笔在通道后台查不到的差异。
         */
        assertThat(r.tradeNo()).isNull();
    }

    @Test
    @DisplayName("没有 openid 就不下单 —— 而且通道一次都不能被调到")
    void 没有openid不发通道() throws Exception {
        RecordingClient client = new RecordingClient();

        var r = gateway(client).prepay(new PayGateway.PrepayCommand(
                "P1", 100L, "CNY", "订单 O1", "JSAPI", null, null));

        assertThat(r.success()).isFalse();
        assertThat(r.message()).contains("openid");
        // 反向控制量：改成「空 openid 照发」，这一条必须变红
        assertThat(client.calls).isZero();
    }

    @Test
    @DisplayName("回调地址没配就不下单 —— 发出去也收不到通知，那笔单会停在 PENDING")
    void 没配回调地址不发通道() throws Exception {
        RecordingClient client = new RecordingClient();
        WechatDirectPayGateway g = gateway(client, APP_ID, "");

        var r = g.prepay(new PayGateway.PrepayCommand(
                "P1", 100L, "CNY", "订单 O1", "JSAPI", null, OPENID));

        assertThat(r.success()).isFalse();
        assertThat(r.message()).contains("notify-url");
        assertThat(client.calls).isZero();
    }

    @Test
    @DisplayName("不是 JSAPI 的支付方式显式拒绝，不静默按 JSAPI 发")
    void 非JSAPI显式拒绝() throws Exception {
        RecordingClient client = new RecordingClient();

        var r = gateway(client).prepay(new PayGateway.PrepayCommand(
                "P1", 100L, "CNY", "订单 O1", "APP", null, OPENID));

        assertThat(r.success()).isFalse();
        assertThat(r.message()).contains("JSAPI");
        // 静默按 JSAPI 发的话，App 用户会拿到一组小程序才用得了的参数
        assertThat(client.calls).isZero();
    }

    @Test
    @DisplayName("通道没返回 prepay_id 算失败，不给端上一组付不了的参数")
    void 缺prepayId算失败() throws Exception {
        RecordingClient client = new RecordingClient();
        client.reply = Map.of("code", "PARAM_ERROR");

        var r = gateway(client).prepay(new PayGateway.PrepayCommand(
                "P1", 100L, "CNY", "订单 O1", "JSAPI", null, OPENID));

        assertThat(r.success()).isFalse();
        assertThat(r.params()).isEmpty();
    }

    // ---------------------------------------------------------------- 查单

    @Test
    @DisplayName("查单走 GET，且 ?mchid= 在路径里（它要参与签名）")
    void 查单走GET带mchid() throws Exception {
        RecordingClient client = new RecordingClient();
        client.reply = Map.of("trade_state", "SUCCESS", "transaction_id", "4200001",
                "amount", Map.of("total", 1));

        var r = gateway(client).query("P1");

        assertThat(client.getApi).isEqualTo("/v3/pay/transactions/out-trade-no/P1?mchid=" + MCH_ID);
        assertThat(client.api).as("不该走 POST").isNull();
        assertThat(r.paid()).isTrue();
        assertThat(r.tradeNo()).isEqualTo("4200001");
        assertThat(r.amountMinor()).isEqualTo(1L);
    }

    @Test
    @DisplayName("USERPAYING 不是已支付 —— 当成已付会给一笔没付的单发货")
    void 用户支付中不算已付() throws Exception {
        RecordingClient client = new RecordingClient();
        client.reply = Map.of("trade_state", "USERPAYING");

        var r = gateway(client).query("P1");

        assertThat(r.ok()).isTrue();
        assertThat(r.paid()).isFalse();
        assertThat(r.found()).isTrue();
    }

    @Test
    @DisplayName("查询失败不是「通道没有这笔」—— 后者会被拿去关单")
    void 查询失败不等于没有这笔() throws Exception {
        RecordingClient client = new RecordingClient();
        client.boom = new ChannelClient.ChannelException("微信 HTTP 502：网关错误", true);

        var r = gateway(client).query("P1");

        assertThat(r.ok()).isFalse();
        // found=false 且 ok=true 才是「可以安全关单」，这里必须不是那个组合
        assertThat(r.ok() && !r.found()).isFalse();
    }

    @Test
    @DisplayName("ORDERNOTEXIST 才是「通道没有这笔」，可以安全关单")
    void 订单不存在可关单() throws Exception {
        RecordingClient client = new RecordingClient();
        client.boom = new ChannelClient.ChannelException("微信 HTTP 404：ORDERNOTEXIST", false);

        var r = gateway(client).query("P1");

        assertThat(r.ok()).isTrue();
        assertThat(r.found()).isFalse();
    }

    @Test
    @DisplayName("查退款是另一套单据，走退款单号的接口")
    void 查退款用退款接口() throws Exception {
        RecordingClient client = new RecordingClient();
        client.reply = Map.of("status", "SUCCESS", "refund_id", "50000",
                "amount", Map.of("refund", 1));

        var r = gateway(client).queryRefund("R1");

        assertThat(client.getApi).isEqualTo("/v3/refund/domestic/refunds/R1");
        assertThat(r.paid()).isTrue();
        assertThat(r.tradeNo()).isEqualTo("50000");
    }

    // ---------------------------------------------------------------- 退款与拒绝

    @Test
    @DisplayName("退款走直连路径，且优先用通道交易号（我方单号重试时会带后缀）")
    void 退款报文() throws Exception {
        RecordingClient client = new RecordingClient();
        client.reply = Map.of("refund_id", "50000000");

        var r = gateway(client).refund(
                new PayGateway.TxContext(null, "4200001", "P1", 100L), 30L, "R1", "七天无理由");

        assertThat(r.success()).isTrue();
        assertThat(client.api).isEqualTo("/v3/refund/domestic/refunds");
        assertThat(client.body).containsEntry("transaction_id", "4200001");
        assertThat(client.body).containsEntry("out_refund_no", "R1");
        assertThat(client.body).containsEntry("amount",
                Map.of("refund", 30L, "total", 100L, "currency", "CNY"));
        assertThat(client.body).doesNotContainKey("sub_mchid");
    }

    @Test
    @DisplayName("补差与分账一律拒绝，且不可重试 —— 直连是归集路径，钱不分账")
    void 补差分账一律拒绝() throws Exception {
        RecordingClient client = new RecordingClient();
        WechatDirectPayGateway g = gateway(client);
        var ctx = new PayGateway.TxContext(null, "4200001", "P1", 100L);

        for (PayGateway.Result r : List.of(
                g.split(ctx, 3L, "S1"),
                g.splitReverse(ctx, 3L, "S2"),
                g.subsidy(ctx, 3L, "B1", "积分补差"),
                g.subsidyReturn(ctx, 3L, "B2", "积分补差回退"))) {
            assertThat(r.success()).isFalse();
            // 重试一万次直连商户号也不会长出分账能力
            assertThat(r.retryable()).isFalse();
        }
        // 一次都不该发出去
        assertThat(client.calls).isZero();
    }

    // ---------------------------------------------------------------- 夹具

    /** 把出站报文接下来。**GET 与 POST 分开记** —— 用错方法是这一层的真实缺陷之一 */
    static final class RecordingClient implements ChannelClient {
        String api;
        String getApi;
        Map<String, Object> body;
        Map<String, Object> reply = Map.of();
        ChannelException boom;
        int calls;

        @Override
        public Map<String, Object> post(String api, Map<String, Object> body) {
            this.calls++;
            this.api = api;
            this.body = body;
            if (boom != null) {
                throw boom;
            }
            return reply;
        }

        @Override
        public Map<String, Object> get(String api) {
            this.calls++;
            this.getApi = api;
            if (boom != null) {
                throw boom;
            }
            return reply;
        }
    }

    private static WechatDirectPayGateway gateway(ChannelClient client) throws Exception {
        return gateway(client, APP_ID, NOTIFY);
    }

    private static WechatDirectPayGateway gateway(ChannelClient client, String appId, String notify)
            throws Exception {
        var kp = WechatApiV3SignerTest.rsa();
        return new WechatDirectPayGateway(client, alwaysCapable(), new SilentRecorder(),
                WechatApiV3SignerTest.signerOf(kp), MCH_ID, appId, notify);
    }

    /** 报文落库不是这组用例的被测对象，接下来丢掉即可 */
    static final class SilentRecorder extends ChannelMessageRecorder {
        final List<String> sent = new ArrayList<>();

        SilentRecorder() {
            super(null, 90);
        }

        @Override
        public void sent(String payChannel, String api, String bizNo,
                         boolean ok, String reason, Map<String, ?> body) {
            sent.add(api);
        }
    }

    /**
     * 能力位全开。其余方法抛异常而不是给 null：
     * <b>碰到别的就是被测代码走了预期外的路</b>，那要当场炸。
     */
    private static PayChannelMasterService alwaysCapable() {
        return new PayChannelMasterService() {
            @Override
            public boolean supportsSubsidy(String payChannel) {
                return true;
            }

            @Override
            public Optional<SysPayChannel> find(String c) {
                throw new UnsupportedOperationException("这组用例不该走到 find");
            }

            @Override
            public List<SysPayChannel> enabled(String market) {
                throw new UnsupportedOperationException("这组用例不该走到 enabled");
            }

            @Override
            public List<String> marketsOf(String payChannel) {
                throw new UnsupportedOperationException("这组用例不该走到 marketsOf");
            }

            @Override
            public String settleCycle(String payChannel) {
                throw new UnsupportedOperationException("这组用例不该走到 settleCycle");
            }

            @Override
            public List<SysPayChannel> all() {
                throw new UnsupportedOperationException("这组用例不该走到 all");
            }

            @Override
            public SysPayChannel updateSettings(
                    String payChannel, Boolean enabled, String markets, String a, String b) {
                throw new UnsupportedOperationException("这组用例不该走到 updateSettings");
            }
        };
    }
}
