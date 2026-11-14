package ai.neargo.shop.pay.channel.http;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 一次已签好名、待发出的渠道 HTTP 请求。协议类（{@code WechatV3Protocol} / {@code AlipayProtocol}）造它，
 * {@link RecordingChannelClient} 发它。
 *
 * @param host         {@code scheme://host[:port]}，不带路径
 * @param pathAndQuery 路径与查询串（微信的 query 参与签名，必须原样发出）
 */
public record ChannelHttpRequest(String method, String host, String pathAndQuery, Map<String, String> headers,
                                 String body) {

    public ChannelHttpRequest {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(pathAndQuery, "pathAndQuery");
        headers = headers == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        body = body == null ? "" : body;
    }

    /** 同一请求换一个主机（主备域名切换） */
    public ChannelHttpRequest withHost(String other) {
        return new ChannelHttpRequest(method, other, pathAndQuery, headers, body);
    }
}
