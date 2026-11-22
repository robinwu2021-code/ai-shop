package ai.neargo.shop.invoice.channel.base;

import ai.neargo.shop.invoice.gateway.ProviderException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * 供应商共用的 HTTP 传输：超时、错误归一。连不上 / 被中断 / HTTP 非 2xx 一律抛 {@link ProviderException}
 * （{@code unreachable} / {@code interrupted} / {@code http_<状态码>}），由模板基类按可重试处理。
 */
public final class HttpTransport {

    private final HttpClient http;
    private final Duration timeout;

    public HttpTransport(Duration timeout) {
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    public Response post(URI uri, String contentType, String body, Map<String, String> headers) {
        var builder = HttpRequest.newBuilder(uri).timeout(timeout).header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        headers.forEach(builder::header);
        HttpResponse<String> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ProviderException("unreachable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException("interrupted", e);
        }
        if (response.statusCode() / 100 != 2) throw new ProviderException("http_" + response.statusCode(), null);
        return new Response(response.statusCode(), response.body(), response.headers().map());
    }

    public record Response(int status, String body, Map<String, java.util.List<String>> headers) {}
}
