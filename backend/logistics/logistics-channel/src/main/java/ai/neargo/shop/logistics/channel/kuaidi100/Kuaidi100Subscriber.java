package ai.neargo.shop.logistics.channel.kuaidi100;

import ai.neargo.shop.logistics.capability.ChannelOutcome;
import ai.neargo.shop.logistics.capability.TrackingSubscriber;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 快递100 订阅（{@code POST /poll}）。订阅成功后，轨迹每有新节点快递100 就推给 {@code /callback/logistics/kuaidi100}。
 *
 * <p>{@code resultv2=4}：要子状态（{@code 501} 投柜或驿站 —— 「已到驿站」只能靠它）与城市路线。
 * 顺丰、顺丰快运、中通要带收件人手机号，否则订阅失败。
 */
@Component
public class Kuaidi100Subscriber implements TrackingSubscriber {

    private static final Logger log = LoggerFactory.getLogger(Kuaidi100Subscriber.class);

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json = new ObjectMapper();
    private final String key;
    private final String salt;
    private final String endpoint;

    public Kuaidi100Subscriber(@Value("${shop.express.kuaidi100.key:}") String key,
                               @Value("${shop.express.kuaidi100.salt:}") String salt,
                               @Value("${shop.express.kuaidi100.host:https://poll.kuaidi100.com}") String host) {
        this.key = key == null ? "" : key.trim();
        this.salt = salt == null ? "" : salt.trim();
        this.endpoint = (host == null || host.isBlank() ? "https://poll.kuaidi100.com" : host.trim()) + "/poll";
    }

    @Override
    public String channel() {
        return "kuaidi100";
    }

    /** 没有 salt 也不行：推送验签要用它，订阅时一并告诉快递100 */
    @Override
    public boolean available() {
        return !key.isEmpty() && !salt.isEmpty();
    }

    @Override
    public ChannelOutcome subscribe(SubscribeCmd cmd) {
        if (cmd.channelCarrierCode() == null) {
            return ChannelOutcome.fatal("NO_CODE", "快递100 里没有承运商 " + cmd.carrier() + " 的编码（lgs_carrier_code）");
        }
        try {
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("callbackurl", cmd.callbackUrl());
            parameters.put("salt", salt);
            parameters.put("resultv2", "4");
            if (cmd.phone() != null && !cmd.phone().isBlank()) {
                parameters.put("phone", cmd.phone().trim());
            }
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("company", cmd.channelCarrierCode());
            p.put("number", cmd.waybillNo().trim());
            p.put("key", key);
            p.put("parameters", parameters);
            String form = "schema=json&param=" + URLEncoder.encode(json.writeValueAsString(p), StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() >= 500) {
                return ChannelOutcome.retryable("HTTP" + resp.statusCode(), "快递100 服务端错误");
            }
            JsonNode n = json.readTree(resp.body());
            return Kuaidi100Codes.classify(n.path("returnCode").asText(""), n.path("message").asText(""));
        } catch (java.io.IOException e) {
            log.warn("[lgs:kuaidi100] 订阅 {} 网络异常：{}", cmd.waybillNo(), e.toString());
            return ChannelOutcome.retryable("IO", e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ChannelOutcome.retryable("INTERRUPTED", "订阅被中断");
        }
    }
}
