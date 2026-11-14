package ai.neargo.shop.pay.channel.wechat;

import ai.neargo.shop.pay.channel.api.ChannelTransportException;
import ai.neargo.shop.pay.channel.http.ChannelHttpResponse;
import ai.neargo.shop.pay.channel.http.RecordingChannelClient;
import ai.neargo.shop.pay.channel.invoke.Invocation;
import java.time.Duration;
import java.util.Map;

/**
 * 微信处理器用的发送口：协议签名 → 记录型客户端发出 → 协议读应答；<b>主备域名切换</b>在这里。
 *
 * <p>只在 IO 失败（连不上、发出后没拿到应答）时切备域名，与原 {@code WechatChannelClient} 同口径；渠道已经答话了的
 * （4xx / 5xx / 验签不过）不切 —— 换个域名答案一样，重发只是把一笔可能已受理的请求再发一次。
 * 主域名那一次若已经发出去了，备域名连不上也不能把这次调用记成「没发出去」。
 */
public final class WechatV3Client {

    static final String CHANNEL = "WECHAT";

    private final WechatV3Protocol protocol;
    private final RecordingChannelClient http;
    private final Duration readTimeout;

    public WechatV3Client(WechatV3Protocol protocol, RecordingChannelClient http, Duration readTimeout) {
        this.protocol = protocol;
        this.http = http;
        this.readTimeout = readTimeout;
    }

    public Map<String, Object> post(Invocation inv, String api, Map<String, Object> body) {
        return call(inv, "POST", api, api, body);
    }

    /** @param pathAndQuery 完整路径，<b>含 query</b>（query 参与签名） */
    public Map<String, Object> get(Invocation inv, String pathAndQuery) {
        int q = pathAndQuery.indexOf('?');
        return call(inv, "GET", q < 0 ? pathAndQuery : pathAndQuery.substring(0, q), pathAndQuery, null);
    }

    private Map<String, Object> call(Invocation inv, String method, String api, String pathAndQuery,
                                     Map<String, Object> body) {
        ChannelHttpResponse resp;
        try {
            resp = http.send(inv, CHANNEL, api, protocol.request(WechatV3Protocol.HOST, method, pathAndQuery, body),
                    readTimeout);
        } catch (ChannelTransportException primary) {
            if (!io(primary)) {
                throw primary;
            }
            boolean primarySent = primary.kind() == ChannelTransportException.Kind.TIMEOUT;
            try {
                resp = http.send(inv, CHANNEL, api,
                        protocol.request(WechatV3Protocol.HOST_BACKUP, method, pathAndQuery, body), readTimeout);
            } catch (ChannelTransportException backup) {
                if (primarySent && inv != null) {
                    // 主域名那次可能已经到了微信：不能因为备域名连不上就记成「没发出去」（那会被当成可安全重发）
                    inv.markSent();
                }
                if (primarySent && backup.kind() == ChannelTransportException.Kind.CONNECT_FAILED) {
                    throw new ChannelTransportException(ChannelTransportException.Kind.TIMEOUT, 0, null,
                            "微信通道不可达（主域名已发出未应答，备域名连不上）", backup);
                }
                throw backup;
            }
        }
        return protocol.read(api, resp);
    }

    private static boolean io(ChannelTransportException e) {
        return e.kind() == ChannelTransportException.Kind.CONNECT_FAILED
                || e.kind() == ChannelTransportException.Kind.TIMEOUT;
    }
}
