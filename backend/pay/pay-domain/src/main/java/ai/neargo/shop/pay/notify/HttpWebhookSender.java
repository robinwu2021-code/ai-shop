package ai.neargo.shop.pay.notify;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Component;

/** JDK HttpClient 实现：<b>不跟随重定向</b>（防止 302 把签名过的通知带去白名单外的地址，安全规范 §2.3） */
@Component
public class HttpWebhookSender implements WebhookSender {

    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(3)).build();

    @Override
    public Response post(String url, Map<String, String> headers, String body, Duration timeout) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        headers.forEach(b::header);
        HttpResponse<String> r = client.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Response(r.statusCode(), r.body());
    }
}
