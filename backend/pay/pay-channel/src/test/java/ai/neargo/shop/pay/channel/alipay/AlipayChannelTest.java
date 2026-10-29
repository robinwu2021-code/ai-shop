package ai.neargo.shop.pay.channel.alipay;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.pay.channel.PayGateway;
import ai.neargo.shop.pay.channel.api.AccessMode;
import ai.neargo.shop.pay.channel.api.ChannelAccountContext;
import ai.neargo.shop.pay.channel.api.ChannelGateway;
import ai.neargo.shop.pay.channel.api.ChannelKey;
import ai.neargo.shop.pay.channel.api.ChannelResult;
import ai.neargo.shop.pay.channel.api.InvocationMeta;
import ai.neargo.shop.pay.channel.api.Money;
import ai.neargo.shop.pay.channel.api.SourceInfo;
import ai.neargo.shop.pay.channel.api.command.SplitCommand;
import ai.neargo.shop.pay.channel.capability.ChannelCapabilityRegistry;
import ai.neargo.shop.pay.channel.context.ChannelContextFactory;
import ai.neargo.shop.pay.channel.context.ChannelRoute;
import ai.neargo.shop.pay.channel.entity.PayChannelCallLog;
import ai.neargo.shop.pay.channel.fault.FaultPolicyRegistry;
import ai.neargo.shop.pay.channel.fault.OutcomeClassifier;
import ai.neargo.shop.pay.channel.handler.ChannelHandlerRegistry;
import ai.neargo.shop.pay.channel.handler.ChannelProductCatalog;
import ai.neargo.shop.pay.channel.http.ChannelHttpRequest;
import ai.neargo.shop.pay.channel.http.ChannelHttpResponse;
import ai.neargo.shop.pay.channel.http.ChannelTransport;
import ai.neargo.shop.pay.channel.http.MaskedPlaintextSealer;
import ai.neargo.shop.pay.channel.http.RecordingChannelClient;
import ai.neargo.shop.pay.channel.invoke.ChannelInvoker;
import ai.neargo.shop.pay.channel.invoke.interceptor.ClassifyInterceptor;
import ai.neargo.shop.pay.channel.invoke.interceptor.RetryInterceptor;
import ai.neargo.shop.pay.channel.invoke.interceptor.ValidationInterceptor;
import ai.neargo.shop.pay.channel.invoke.record.CallLogStore;
import ai.neargo.shop.pay.channel.legacy.LegacyChannelBridge;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * 支付宝直付通处理器（TDD-支付服务-W2渠道层补完 S5 · AC6）。报文自 {@code AlipayPayGateway} 搬来；新的是
 * {@link AlipayProtocol}：RSA2 请求签名、应答验签（验的是 {@code xxx_response} 那一段<b>原文</b>，不是解析后再序列化的）。
 */
class AlipayChannelTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final KeyPair app = rsa();
    private final KeyPair alipay = rsa();
    private final FakeAlipay peer = new FakeAlipay();

    @Test
    @DisplayName("★★★ 请求按 RSA2 签名：去掉 sign 与空值、按键排序拼串（sign_type 参与签名，与验回调不同）；用应用公钥验得过")
    void requestIsSignedRsa2() throws Exception {
        LegacyChannelBridge bridge = bridge("PLATFORM");
        peer.script = method -> Map.of("code", "10000", "out_trade_no", "OD-1", "trade_status", "TRADE_SUCCESS", "trade_no", "2026T1",
                "total_amount", "12.34");

        var res = bridge.query("ALIPAY", "OD-1");

        Map<String, String> form = form(peer.last().body());
        assertThat(peer.last().method()).isEqualTo("POST");
        assertThat(peer.last().host() + peer.last().pathAndQuery()).isEqualTo(AlipayProtocol.GATEWAY);
        assertThat(form).containsEntry("method", "alipay.trade.query").containsEntry("app_id", "2021APP")
                .containsEntry("sign_type", "RSA2").containsEntry("charset", "utf-8");
        assertThat(form.get("biz_content")).contains("\"out_trade_no\":\"OD-1\"");
        Signature v = Signature.getInstance("SHA256withRSA");
        v.initVerify(app.getPublic());
        v.update(AlipayProtocol.signContent(form).getBytes(StandardCharsets.UTF_8));
        assertThat(v.verify(Base64.getDecoder().decode(form.get("sign")))).isTrue();

        assertThat(res.paid()).isTrue();
        assertThat(res.amountMinor()).isEqualTo(1234L);
        assertThat(res.tradeNo()).isEqualTo("2026T1");
    }

    @Test
    @DisplayName("★★★ TRADE_NOT_EXIST 才是「没有这笔」；WAIT_BUYER_PAY 是未付；应答验签不过 = 查不通")
    void queryOutcomes() {
        LegacyChannelBridge bridge = bridge("PLATFORM");
        peer.script = m -> Map.of("code", "40004", "sub_code", "ACQ.TRADE_NOT_EXIST", "msg", "Business Failed");
        var missing = bridge.query("ALIPAY", "OD-1");
        assertThat(missing.ok()).isTrue();
        assertThat(missing.found()).isFalse();

        peer.script = m -> Map.of("code", "10000", "trade_status", "WAIT_BUYER_PAY");
        var unpaid = bridge.query("ALIPAY", "OD-1");
        assertThat(unpaid.found()).isTrue();
        assertThat(unpaid.paid()).isFalse();

        peer.script = m -> Map.of("code", "10000", "trade_status", "TRADE_SUCCESS", "total_amount", "1.00");
        peer.forge = true;
        assertThat(bridge.query("ALIPAY", "OD-1").ok()).isFalse();
    }

    @Test
    @DisplayName("退款：金额按元两位小数、多次退款各自 out_request_no；受理回执给 trade_no")
    void refund() {
        LegacyChannelBridge bridge = bridge("PLATFORM");
        peer.script = m -> Map.of("code", "10000", "trade_no", "2026T1", "fund_change", "Y");

        var res = bridge.refund("ALIPAY", new PayGateway.TxContext(null, "2026T1", "OD-1", 100L), 30L, "R1", "少发");

        assertThat(res.success()).isTrue();
        assertThat(res.providerNo()).isEqualTo("2026T1");
        Map<String, String> form = form(peer.last().body());
        assertThat(form).containsEntry("method", "alipay.trade.refund");
        assertThat(form.get("biz_content")).contains("\"refund_amount\":\"0.30\"").contains("\"out_request_no\":\"R1\"")
                .contains("\"trade_no\":\"2026T1\"");
    }

    @Test
    @DisplayName("★★ 补差没配平台出资账号 → 明确失败不发；配了 → 反向分账（trans_out = 平台）")
    void subsidyNeedsPlatformAccount() {
        LegacyChannelBridge none = bridge("");
        ChannelResult denied = gateway.execute(context(), split(SplitCommand.Kind.SUBSIDY));
        assertThat(denied).isInstanceOfSatisfying(ChannelResult.Failed.class,
                f -> assertThat(f.channelMessage()).contains("platform-account"));
        assertThat(peer.requests).isEmpty();
        assertThat(none).isNotNull();

        bridge("PLATFORM");
        peer.script = m -> Map.of("code", "10000", "trade_no", "2026T1");
        ChannelResult ok = gateway.execute(context(), split(SplitCommand.Kind.SUBSIDY));
        assertThat(ok).isInstanceOf(ChannelResult.Succeeded.class);
        Map<String, String> form = form(peer.last().body());
        assertThat(form).containsEntry("method", "alipay.trade.order.settle");
        assertThat(form.get("biz_content")).contains("\"trans_out\":\"PLATFORM\"").contains("\"trans_in\":\"SUB-2088\"")
                .contains("\"amount\":\"0.03\"");
    }

    // ---------------------------------------------------------------- 夹具

    private ChannelGateway gateway;
    private ChannelContextFactory contexts;

    private LegacyChannelBridge bridge(String platformAccount) {
        CallLogStore calls = new CallLogStore() {
            @Override
            public Long insert(PayChannelCallLog row) {
                return 1L;
            }

            @Override
            public void update(PayChannelCallLog row) {
            }

            @Override
            public Optional<PayChannelCallLog> lastSucceeded(String c, String o, String n) {
                return Optional.empty();
            }
        };
        RecordingChannelClient http = new RecordingChannelClient(peer, calls, row -> 1L, new MaskedPlaintextSealer(),
                null, 30);
        AlipayProtocol protocol = new AlipayProtocol("2021APP", Base64.getEncoder().encodeToString(app.getPrivate().getEncoded()),
                Base64.getEncoder().encodeToString(alipay.getPublic().getEncoded()),
                Clock.fixed(Instant.parse("2026-10-26T02:00:00Z"), ZoneOffset.UTC));
        AlipayChannel channel = new AlipayChannel(new AlipayClient(protocol, http, Duration.ofSeconds(5)),
                platformAccount, ch -> true);
        ChannelCapabilityRegistry caps = new ChannelCapabilityRegistry(List.of(channel));
        ChannelProductCatalog catalog = new ChannelProductCatalog() {
            @Override
            public Set<ChannelKey> enabledPayKeys() {
                return Set.of();
            }

            @Override
            public boolean payEnabled(ChannelKey key) {
                return true;
            }
        };
        ChannelHandlerRegistry handlers = new ChannelHandlerRegistry(caps.handlers(), catalog);
        gateway = new ChannelInvoker(handlers, List.of(new ValidationInterceptor(),
                new RetryInterceptor(new FaultPolicyRegistry()), new ClassifyInterceptor(new OutcomeClassifier(List.of()))),
                List.of());
        contexts = new ChannelContextFactory(caps, catalog);
        assertThat(caps.defaultAccessMode("ALIPAY")).contains(AccessMode.SERVICE_PROVIDER);
        return new LegacyChannelBridge(gateway, handlers, caps, contexts);
    }

    private ChannelAccountContext context() {
        return contexts.from(ChannelRoute.of("ALIPAY", "CNY", "SUB-2088"));
    }

    private static SplitCommand split(SplitCommand.Kind kind) {
        return new SplitCommand(new InvocationMeta(null, null, null, null, null, SourceInfo.system("T", "B")), kind,
                "SUB-2088", "2026T1", "OD-1", new Money(100, "CNY"), new Money(3, "CNY"), "REQ-" + kind, "积分补差");
    }

    private static Map<String, String> form(String body) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            int i = pair.indexOf('=');
            out.put(URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    private static KeyPair rsa() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            return g.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 假支付宝：按方法名给 {@code xxx_response}，用支付宝私钥对那一段原文签名 */
    final class FakeAlipay implements ChannelTransport {
        final List<ChannelHttpRequest> requests = new ArrayList<>();
        Function<String, Map<String, String>> script = m -> Map.of("code", "10000");
        boolean forge;

        @Override
        public ChannelHttpResponse exchange(ChannelHttpRequest request, Duration readTimeout) {
            requests.add(request);
            String method = form(request.body()).get("method");
            String content = JSON.writeValueAsString(new LinkedHashMap<>(script.apply(method)));
            String sign;
            try {
                Signature s = Signature.getInstance("SHA256withRSA");
                s.initSign(alipay.getPrivate());
                s.update((forge ? content + " " : content).getBytes(StandardCharsets.UTF_8));
                sign = Base64.getEncoder().encodeToString(s.sign());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            String body = "{\"" + method.replace('.', '_') + "_response\":" + content + ",\"sign\":\"" + sign + "\"}";
            return new ChannelHttpResponse(200, Map.of("Content-Type", "application/json;charset=utf-8"), body);
        }

        ChannelHttpRequest last() {
            return requests.getLast();
        }
    }
}
