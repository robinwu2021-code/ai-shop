package ai.neargo.shop.logistics.channel.wx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * 微信物流查询插件的服务端接口（{@code /cgi-bin/express/delivery/open_msg/*}）与 access_token。
 *
 * <p>换 token（trace_waybill）、查状态（query_trace）、插件展示共用这一份 stable_token 缓存 ——
 * 各自取各自的，一个小程序同时持有好几份 token，互相不知道。
 */
@Component
public class WxLogisticsClient {

    private final String host;
    private final String appid;
    private final String secret;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();
    private volatile String token;
    private volatile long tokenExpireAt;

    public WxLogisticsClient(@Value("${shop.wx.host:https://api.weixin.qq.com}") String host,
                             @Value("${shop.wx.appid:}") String appid,
                             @Value("${shop.wx.secret:}") String secret) {
        this.host = host;
        this.appid = appid == null ? "" : appid.trim();
        this.secret = secret == null ? "" : secret.trim();
    }

    public boolean configured() {
        return !appid.isEmpty() && !secret.isEmpty();
    }

    /** POST 一个 open_msg 接口，返回原始 JSON。网络异常与取 token 失败原样抛出（调用方归为可重试） */
    JsonNode post(String api, Map<String, Object> body, Duration timeout) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(host + "/cgi-bin/express/delivery/open_msg/" + api + "?access_token=" + accessToken()))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        return json.readTree(http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body());
    }

    String accessToken() throws Exception {
        long now = System.currentTimeMillis();
        String t = token;
        if (t != null && now < tokenExpireAt) {
            return t;
        }
        String body = json.writeValueAsString(Map.of(
                "grant_type", "client_credential", "appid", appid, "secret", secret));
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(host + "/cgi-bin/stable_token"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        JsonNode n = json.readTree(http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body());
        String got = n.path("access_token").asText("");
        if (got.isBlank()) {
            throw new IllegalStateException("取 access_token 失败：" + n.path("errmsg").asText(""));
        }
        // 提前 5 分钟过期，免得卡在边界上用一个刚失效的 token
        tokenExpireAt = now + (n.path("expires_in").asLong(7200) - 300) * 1000L;
        token = got;
        return got;
    }
}
