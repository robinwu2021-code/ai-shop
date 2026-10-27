package ai.neargo.shop.pay.channel.alipay;

import ai.neargo.shop.pay.channel.http.RecordingChannelClient;
import ai.neargo.shop.pay.channel.invoke.Invocation;
import java.time.Duration;
import java.util.Map;

/** 支付宝处理器的发送口：协议签名 → 记录型客户端发出 → 协议验签取出 {@code xxx_response} */
public final class AlipayClient {

    static final String CHANNEL = "ALIPAY";

    private final AlipayProtocol protocol;
    private final RecordingChannelClient http;
    private final Duration readTimeout;

    public AlipayClient(AlipayProtocol protocol, RecordingChannelClient http, Duration readTimeout) {
        this.protocol = protocol;
        this.http = http;
        this.readTimeout = readTimeout;
    }

    public Map<String, Object> call(Invocation inv, String method, Map<String, Object> bizContent) {
        return protocol.read(method, http.send(inv, CHANNEL, method, protocol.request(method, bizContent), readTimeout));
    }
}
