package ai.neargo.shop.pay.channel.http;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * JDK {@link HttpClient} 实现。整个支付渠道层<b>只有这一处</b>直接用 HTTP 客户端。
 *
 * <p><b>锁 HTTP/1.1</b>：渠道网关走 1.1，而 JDK HttpClient 协商 HTTP/2 时对大 body 有已知的挂起问题（java.md §四）。
 * 受限头（{@code Host}、{@code Content-Length}…）由 JDK 自己写，传进来会被拒，这里跳过。
 */
public final class JdkChannelTransport implements ChannelTransport {

    private static final Set<String> RESTRICTED = Set.of("host", "content-length", "connection",
            "expect", "upgrade");

    private final HttpClient http;

    public JdkChannelTransport(Duration connectTimeout) {
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout)
                .build();
    }

    @Override
    public ChannelHttpResponse exchange(ChannelHttpRequest request, Duration readTimeout)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(request.host() + request.pathAndQuery()))
                .timeout(readTimeout);
        request.headers().forEach((k, v) -> {
            if (k != null && v != null && !RESTRICTED.contains(k.toLowerCase(Locale.ROOT))) {
                b.header(k, v);
            }
        });
        if ("GET".equalsIgnoreCase(request.method())) {
            b.GET();
        } else {
            b.method(request.method().toUpperCase(Locale.ROOT),
                    HttpRequest.BodyPublishers.ofString(request.body()));
        }
        HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        Map<String, String> headers = new LinkedHashMap<>();
        resp.headers().map().forEach((k, v) -> headers.put(k, v == null || v.isEmpty() ? null : first(v)));
        return new ChannelHttpResponse(resp.statusCode(), headers, resp.body());
    }

    private static String first(List<String> values) {
        return values.getFirst();
    }
}
