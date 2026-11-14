package ai.neargo.shop.pay.channel.wechat;

import ai.neargo.shop.pay.channel.PayGateway;
import ai.neargo.shop.pay.channel.WechatApiV3Signer;
import ai.neargo.shop.pay.channel.api.ChannelAccountContext;
import ai.neargo.shop.pay.channel.api.ChannelGateway;
import ai.neargo.shop.pay.channel.api.ChannelKey;
import ai.neargo.shop.pay.channel.capability.ChannelCapability;
import ai.neargo.shop.pay.channel.capability.ChannelCapabilityRegistry;
import ai.neargo.shop.pay.channel.context.ChannelContextFactory;
import ai.neargo.shop.pay.channel.context.ChannelRoute;
import ai.neargo.shop.pay.channel.entity.PayChannelCallLog;
import ai.neargo.shop.pay.channel.entity.PayRawPayload;
import ai.neargo.shop.pay.channel.fault.ChannelErrorMapper;
import ai.neargo.shop.pay.channel.fault.FaultPolicy;
import ai.neargo.shop.pay.channel.fault.FaultPolicyRegistry;
import ai.neargo.shop.pay.channel.fault.OutcomeClassifier;
import ai.neargo.shop.pay.channel.api.ChannelOperation;
import ai.neargo.shop.pay.channel.handler.ChannelHandlerRegistry;
import ai.neargo.shop.pay.channel.handler.ChannelProductCatalog;
import ai.neargo.shop.pay.channel.http.ChannelHttpRequest;
import ai.neargo.shop.pay.channel.http.ChannelHttpResponse;
import ai.neargo.shop.pay.channel.http.ChannelTransport;
import ai.neargo.shop.pay.channel.http.MaskedPlaintextSealer;
import ai.neargo.shop.pay.channel.http.RecordingChannelClient;
import ai.neargo.shop.pay.channel.invoke.ChannelInvoker;
import ai.neargo.shop.pay.channel.invoke.interceptor.ClassifyInterceptor;
import ai.neargo.shop.pay.channel.invoke.interceptor.RecordingInterceptor;
import ai.neargo.shop.pay.channel.invoke.interceptor.RetryInterceptor;
import ai.neargo.shop.pay.channel.invoke.interceptor.TraceInterceptor;
import ai.neargo.shop.pay.channel.invoke.interceptor.ValidationInterceptor;
import ai.neargo.shop.pay.channel.invoke.record.CallLogStore;
import ai.neargo.shop.pay.channel.invoke.record.ChannelCallRecorder;
import ai.neargo.shop.pay.channel.legacy.LegacyChannelBridge;
import ai.neargo.shop.pay.channel.wechat.direct.WechatDirectChannel;
import java.io.IOException;
import java.net.ConnectException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * 渠道处理器的测试台：<b>真协议 + 真记录型客户端 + 真管线（校验、记录、重试、分类）+ 真桥</b>，只有对端是假的。
 *
 * <p>W2 S5 的验收是「CN 行为不变」—— 行为是调用方从桥上拿到的旧类型结果，所以这里从桥往下整条走，而不是只测处理器：
 * 只测处理器测不到「管线怎么归类它抛的异常、桥怎么译回旧结果」，而换处理器最容易改坏的恰恰是那两步。
 */
final class Rig {

    static final String MCH_ID = "1900000109";
    static final String APP_ID = "wxTESTAPPID";
    static final String NOTIFY = "https://example.test/callback/pay/channel/WECHAT";
    static final String OPENID = "oABCDEFGHIJKLMN";

    final KeyPair keys = rsa();
    /** 测试里商户私钥与「平台」公钥是同一对：假对端用同一把私钥给应答签名，协议用公钥验得过 */
    final WechatApiV3Signer signer = new WechatApiV3Signer(MCH_ID, "SERIAL",
            Base64.getEncoder().encodeToString(keys.getPrivate().getEncoded()),
            Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()));
    final FakeWechat peer = new FakeWechat(signer);
    final Calls calls = new Calls();
    final List<PayRawPayload> raws = new ArrayList<>();
    final RecordingChannelClient http = new RecordingChannelClient(peer, calls, row -> {
        raws.add(row);
        return (long) raws.size();
    }, new MaskedPlaintextSealer(), null, 30);
    final WechatV3Protocol protocol = new WechatV3Protocol(signer);
    final WechatV3Client client = new WechatV3Client(protocol, http, Duration.ofSeconds(5));

    String channel;
    ChannelCapabilityRegistry capabilities;
    ChannelHandlerRegistry handlers;
    ChannelGateway gateway;
    LegacyChannelBridge bridge;

    Rig with(ChannelCapability... caps) {
        channel = caps[0].payChannel();
        capabilities = new ChannelCapabilityRegistry(List.of(caps));
        handlers = new ChannelHandlerRegistry(capabilities.handlers(), catalog());
        FaultPolicyRegistry policies = new FaultPolicyRegistry();
        // 纯读的重试间隔压到 0，测试不睡
        policies.override(caps[0].payChannel(), ChannelOperation.QUERY,
                new FaultPolicy(Duration.ofSeconds(10), 3, true, Duration.ZERO, 32, 50f, 20, Duration.ofSeconds(30)));
        gateway = new ChannelInvoker(handlers, List.of(new TraceInterceptor(), new ValidationInterceptor(),
                new RecordingInterceptor(new ChannelCallRecorder(calls)), new RetryInterceptor(policies),
                new ClassifyInterceptor(new OutcomeClassifier(List.<ChannelErrorMapper>of(new WechatErrorMapper())))),
                List.of());
        bridge = new LegacyChannelBridge(gateway, handlers, capabilities, new ChannelContextFactory(capabilities, catalog()));
        return this;
    }

    /** 与桥同一口径的上下文（直接对门面发命令的用例用） */
    ChannelAccountContext bridgeContext() {
        return new ChannelContextFactory(capabilities, catalog()).from(ChannelRoute.of(channel, "CNY", null));
    }

    /** 直连处理器，可换 appid / 回调地址 */
    WechatDirectChannel direct(String appId, String notify) {
        return new WechatDirectChannel(client, protocol, MCH_ID, appId, notify, null);
    }

    static PayGateway.PrepayCommand prepay(String out, long amount, String openid) {
        return new PayGateway.PrepayCommand(out, amount, "CNY", "订单 O1", "JSAPI", null, openid);
    }

    static ChannelProductCatalog catalog() {
        return new ChannelProductCatalog() {
            @Override
            public Set<ChannelKey> enabledPayKeys() {
                return Set.of();
            }

            @Override
            public boolean payEnabled(ChannelKey key) {
                return true;
            }
        };
    }

    static KeyPair rsa() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            return g.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 假的微信：记下每个请求，按脚本给（签好名的）应答；可让主域名连不上 */
    static final class FakeWechat implements ChannelTransport {
        final List<ChannelHttpRequest> requests = new ArrayList<>();
        final WechatApiV3Signer signer;
        Function<ChannelHttpRequest, ChannelHttpResponse> script = r -> json(200, "{}");
        boolean primaryDown;
        boolean badSignature;

        FakeWechat(WechatApiV3Signer signer) {
            this.signer = signer;
        }

        @Override
        public ChannelHttpResponse exchange(ChannelHttpRequest request, Duration readTimeout) throws IOException {
            requests.add(request);
            if (primaryDown && request.host().equals(WechatV3Protocol.HOST)) {
                throw new ConnectException("primary down");
            }
            ChannelHttpResponse r = script.apply(request);
            String ts = "1700000000";
            String nonce = "NONCE";
            Map<String, String> headers = new LinkedHashMap<>(r.headers());
            headers.put("Wechatpay-Timestamp", ts);
            headers.put("Wechatpay-Nonce", nonce);
            headers.put("Wechatpay-Signature", badSignature ? "AAAA"
                    : signer.sign(WechatApiV3Signer.responseSignContent(ts, nonce, r.body())));
            return new ChannelHttpResponse(r.status(), headers, r.body());
        }

        static ChannelHttpResponse json(int status, String body) {
            return new ChannelHttpResponse(status, Map.of("Content-Type", "application/json"), body);
        }

        ChannelHttpRequest last() {
            return requests.getLast();
        }
    }

    static final class Calls implements CallLogStore {
        final List<PayChannelCallLog> rows = new ArrayList<>();
        private final AtomicLong ids = new AtomicLong();

        @Override
        public synchronized Long insert(PayChannelCallLog row) {
            row.setId(ids.incrementAndGet());
            rows.add(row);
            return row.getId();
        }

        @Override
        public synchronized void update(PayChannelCallLog patch) {
            PayChannelCallLog r = rows.stream().filter(x -> x.getId().equals(patch.getId())).findFirst().orElseThrow();
            for (var f : PayChannelCallLog.class.getDeclaredFields()) {
                f.setAccessible(true);
                try {
                    Object v = f.get(patch);
                    if (v != null) {
                        f.set(r, v);
                    }
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        }

        @Override
        public Optional<PayChannelCallLog> lastSucceeded(String payChannel, String operation, String outNo) {
            return Optional.empty();
        }
    }
}
