package ai.neargo.shop.pay.channel.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.pay.channel.ChannelMessageRecorder;
import ai.neargo.shop.pay.channel.api.AccessMode;
import ai.neargo.shop.pay.channel.api.ChannelAccountContext;
import ai.neargo.shop.pay.channel.api.ChannelKey;
import ai.neargo.shop.pay.channel.api.ChannelTransportException;
import ai.neargo.shop.pay.channel.api.InvocationMeta;
import ai.neargo.shop.pay.channel.api.PayScene;
import ai.neargo.shop.pay.channel.api.SourceInfo;
import ai.neargo.shop.pay.channel.api.command.QueryCommand;
import ai.neargo.shop.pay.channel.entity.PayChannelCallLog;
import ai.neargo.shop.pay.channel.entity.PayRawPayload;
import ai.neargo.shop.pay.channel.invoke.Invocation;
import ai.neargo.shop.pay.channel.invoke.record.CallLogStore;
import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 记录型客户端（TDD-支付服务-W2渠道层补完 S3 · AC4）。
 *
 * <p>I2 的调用记录在发送前写了一行，但 {@code http_method} / {@code url_path} 恒为占位 {@code -}、报文一个字都没有 ——
 * 「我们到底发了什么、渠道回了什么」这一条线索断在最该有的地方。这里守：真实方法与路径、请求 / 响应的脱敏副本、
 * 原文落 {@code pay_raw_payload}（密钥模块前存脱敏全文）、发出与否如实标记、记录失败不挡发送。
 */
class RecordingChannelClientTest {

    private static final String SECRET_AUTH = "WECHATPAY2-SHA256-RSA2048 mchid=\"1\",signature=\"S3CR3T\"";

    @Test
    @DisplayName("★★★ AC4 调用记录写真实方法与路径、请求 / 响应脱敏副本与原文引用；签名头不落明文")
    void recordsRealCoordinatesAndMaskedPayloads() throws Exception {
        Fixture f = new Fixture();
        f.transport.reply = new ChannelHttpResponse(200, Map.of("Wechatpay-Signature", "RESP-SIG"),
                "{\"prepay_id\":\"wx1\",\"nonce\":\"N1\"}");
        Invocation inv = f.invocation(7L);

        ChannelHttpResponse resp = f.client.send(inv, "WECHAT", "JSAPI", jsapiRequest(), Duration.ofSeconds(5));

        assertThat(resp.body()).contains("wx1");
        PayChannelCallLog row = f.calls.updates.getLast();
        assertThat(row.getId()).isEqualTo(7L);
        assertThat(f.calls.merged().getHttpMethod()).isEqualTo("POST");
        assertThat(f.calls.merged().getUrlPath()).isEqualTo("/v3/pay/transactions/jsapi");
        assertThat(f.calls.merged().getHttpStatus()).isEqualTo(200);
        assertThat(f.calls.merged().getRequestMasked()).contains("OD-1").doesNotContain("S3CR3T");
        assertThat(f.calls.merged().getResponseMasked()).contains("wx1").doesNotContain("N1").doesNotContain("RESP-SIG");
        assertThat(f.calls.merged().getRequestRawId()).isNotNull();
        assertThat(f.calls.merged().getResponseRawId()).isNotNull();

        assertThat(f.raws.rows).extracting(PayRawPayload::getKind).containsExactly("CALL_REQUEST", "CALL_RESPONSE");
        PayRawPayload req = f.raws.rows.getFirst();
        // 密钥模块（W5）就绪前：存脱敏全文，明文哈希举证
        assertThat(req.getStorage()).isEqualTo("DB");
        assertThat(req.getCipherText()).contains("OD-1").doesNotContain("S3CR3T");
        assertThat(req.getDataKeyCipher()).isEqualTo(MaskedPlaintextSealer.NOT_ENCRYPTED);
        assertThat(req.getSha256()).isEqualTo(sha256(f.transport.lastPlain));
        assertThat(req.getRetainUntil()).isNotNull();

        assertThat(inv.requestSent()).isTrue();
        // 旧报文表（shop.pay.log.recording=both）照写一行
        assertThat(f.legacy.sent).containsExactly("WECHAT JSAPI OD-1 true");
    }

    @Test
    @DisplayName("★★ AC4 路径里查询串的敏感参数遮掉，其余照留（排障要看 mchid / sub_mchid）")
    void queryStringIsMaskedInPath() throws Exception {
        Fixture f = new Fixture();
        f.transport.reply = new ChannelHttpResponse(200, Map.of(), "{}");

        f.client.send(f.invocation(1L), "WECHAT", "QUERY", new ChannelHttpRequest("GET", "https://api.mch.weixin.qq.com",
                "/v3/pay/transactions/out-trade-no/OD-1?mchid=19000&sign=abc", Map.of(), ""), Duration.ofSeconds(5));

        assertThat(f.calls.merged().getHttpMethod()).isEqualTo("GET");
        assertThat(f.calls.merged().getUrlPath())
                .isEqualTo("/v3/pay/transactions/out-trade-no/OD-1?mchid=19000&sign=***");
    }

    @Test
    @DisplayName("★★★ AC4 连接没建成 = 没发出去（可安全重发）；连上后超时 = 发出去了（不明）")
    void connectFailureIsNotSentButTimeoutIs() {
        Fixture f = new Fixture();
        f.transport.boom = new ConnectException("refused");
        Invocation refused = f.invocation(1L);
        assertThatThrownBy(() -> f.client.send(refused, "WECHAT", "JSAPI", jsapiRequest(), Duration.ofSeconds(5)))
                .isInstanceOfSatisfying(ChannelTransportException.class,
                        e -> assertThat(e.kind()).isEqualTo(ChannelTransportException.Kind.CONNECT_FAILED));
        assertThat(refused.requestSent()).isFalse();

        f.transport.boom = new HttpTimeoutException("read timed out");
        Invocation timedOut = f.invocation(2L);
        assertThatThrownBy(() -> f.client.send(timedOut, "WECHAT", "JSAPI", jsapiRequest(), Duration.ofSeconds(5)))
                .isInstanceOfSatisfying(ChannelTransportException.class,
                        e -> assertThat(e.kind()).isEqualTo(ChannelTransportException.Kind.TIMEOUT));
        assertThat(timedOut.requestSent()).isTrue();
        // 发出去之前的那一半照样记下了
        assertThat(f.raws.rows).extracting(PayRawPayload::getKind).containsOnly("CALL_REQUEST");
    }

    @Test
    @DisplayName("★★ 记录写不进去不挡发送；管线外（进件）没有调用记录行也照样落原文")
    void recordingFailureDoesNotBlockAndWorksOutsidePipeline() throws Exception {
        Fixture f = new Fixture();
        f.calls.broken = true;
        f.raws.broken = true;
        f.transport.reply = new ChannelHttpResponse(200, Map.of(), "{\"ok\":1}");
        assertThat(f.client.send(f.invocation(3L), "WECHAT", "JSAPI", jsapiRequest(), Duration.ofSeconds(5)).status())
                .isEqualTo(200);
        assertThat(f.client.recordingFailures()).isPositive();

        Fixture outside = new Fixture();
        outside.transport.reply = new ChannelHttpResponse(200, Map.of(), "{\"applyment_id\":1}");
        outside.client.send(null, "WECHAT", "/v3/ecommerce/applyments/", jsapiRequest(), Duration.ofSeconds(5));
        assertThat(outside.calls.updates).isEmpty();
        assertThat(outside.raws.rows).hasSize(2);
    }

    // ---------------------------------------------------------------- 夹具

    private static ChannelHttpRequest jsapiRequest() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", SECRET_AUTH);
        headers.put("Content-Type", "application/json");
        return new ChannelHttpRequest("POST", "https://api.mch.weixin.qq.com", "/v3/pay/transactions/jsapi", headers,
                "{\"out_trade_no\":\"OD-1\",\"amount\":{\"total\":1}}");
    }

    private static String sha256(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    static final class Fixture {
        final FakeTransport transport = new FakeTransport();
        final Calls calls = new Calls();
        final Raws raws = new Raws();
        final Legacy legacy = new Legacy();
        final RecordingChannelClient client = new RecordingChannelClient(transport, calls, raws,
                new MaskedPlaintextSealer(), legacy, 1825);

        Invocation invocation(long callLogId) {
            ChannelAccountContext ctx = new ChannelAccountContext(null, "WECHAT", AccessMode.MERCHANT_OWN, null, null,
                    null, "CNY", 2);
            QueryCommand cmd = new QueryCommand(new InvocationMeta(null, null, null, null, null,
                    SourceInfo.system("T", "OD-1")), "OD-1");
            Invocation inv = Invocation.start(new ChannelKey(null, "WECHAT", AccessMode.MERCHANT_OWN, PayScene.ANY,
                    cmd.operation()), ctx, cmd);
            inv.callLogId(callLogId);
            return inv;
        }
    }

    static final class FakeTransport implements ChannelTransport {
        ChannelHttpResponse reply;
        IOException boom;
        String lastPlain;

        @Override
        public ChannelHttpResponse exchange(ChannelHttpRequest request, Duration readTimeout) throws IOException {
            lastPlain = RecordingChannelClient.plainOf(request);
            if (boom != null) {
                throw boom;
            }
            return reply;
        }
    }

    static final class Calls implements CallLogStore {
        final List<PayChannelCallLog> updates = new ArrayList<>();
        boolean broken;

        @Override
        public Long insert(PayChannelCallLog row) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void update(PayChannelCallLog row) {
            if (broken) {
                throw new IllegalStateException("db down");
            }
            updates.add(row);
        }

        @Override
        public Optional<PayChannelCallLog> lastSucceeded(String payChannel, String operation, String outNo) {
            return Optional.empty();
        }

        PayChannelCallLog merged() {
            PayChannelCallLog m = new PayChannelCallLog();
            for (PayChannelCallLog u : updates) {
                if (u.getHttpMethod() != null) {
                    m.setHttpMethod(u.getHttpMethod());
                }
                if (u.getUrlPath() != null) {
                    m.setUrlPath(u.getUrlPath());
                }
                if (u.getHttpStatus() != null) {
                    m.setHttpStatus(u.getHttpStatus());
                }
                if (u.getRequestMasked() != null) {
                    m.setRequestMasked(u.getRequestMasked());
                }
                if (u.getResponseMasked() != null) {
                    m.setResponseMasked(u.getResponseMasked());
                }
                if (u.getRequestRawId() != null) {
                    m.setRequestRawId(u.getRequestRawId());
                }
                if (u.getResponseRawId() != null) {
                    m.setResponseRawId(u.getResponseRawId());
                }
            }
            return m;
        }
    }

    static final class Raws implements RawPayloadStore {
        final List<PayRawPayload> rows = new ArrayList<>();
        boolean broken;

        @Override
        public Long insert(PayRawPayload row) {
            if (broken) {
                throw new IllegalStateException("db down");
            }
            row.setId((long) rows.size() + 100);
            rows.add(row);
            return row.getId();
        }
    }

    static final class Legacy extends ChannelMessageRecorder {
        final List<String> sent = new ArrayList<>();

        Legacy() {
            super(null, 90);
        }

        @Override
        public void sent(String payChannel, String api, String bizNo, boolean ok, String reason, Map<String, ?> body) {
            sent.add(payChannel + " " + api + " " + bizNo + " " + ok);
        }
    }
}
