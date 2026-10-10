package ai.neargo.shop.notify.port;

import ai.neargo.shop.spi.notify.PushGateway;
import ai.neargo.shop.spi.notify.PushProvider;
import ai.neargo.shop.spi.notify.SendResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Apple APNs（iOS 直连，设计 · 需求 2 · P3）。2026-08 核对自官方文档。
 *
 * <p><b>为什么直连而不只靠个推透传</b>：个推免费档下 iOS 走它的 APNs 透传够日常用，
 * 但这条直连脱离聚合商依赖、便于富媒体与精细化（ADR-018 留的口子）。
 *
 * <p><b>不引 pushy / apns 库</b>：同 {@link GetuiPushGateway} 的离线约束。用到的只有
 * 一枚 ES256 provider token（{@link PushCrypto}）+ 一次 HTTP/2 POST。
 *
 * <p><b>provider token 缓存 ~50 分钟</b>：Apple 要求 token 在 20~60 分钟间刷新，
 * <b>刷太勤会回 429 TooManyProviderTokenUpdates</b>——不是「慢」，是被限流。
 * 一枚 token 可服务该 team 下所有推送，故全局缓存一枚。
 *
 * <p>默认不启用（{@code shop.push.apns.stub} 默认 true）。开启需
 * {@code APNS_TEAM_ID/APNS_KEY_ID/APNS_PRIVATE_KEY/APNS_TOPIC}。
 */
@Component
@ConditionalOnProperty(name = "shop.push.apns.stub", havingValue = "false")
public class ApnsPushGateway implements PushGateway {

    private static final Logger log = LoggerFactory.getLogger(ApnsPushGateway.class);

    private static final String STR_FIELD = "\"%s\"\\s*:\\s*\"([^\"]*)\"";

    // HTTP/2 是 APNs 的硬要求；JDK HttpClient 默认就协商 h2
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(5)).build();

    private final String host;
    private final String teamId;
    private final String keyId;
    private final String topic;
    private final PrivateKey privateKey;

    private volatile String providerToken;
    private volatile long tokenRefreshAt;

    public ApnsPushGateway(@Value("${shop.push.apns.host:https://api.push.apple.com}") String host,
                           @Value("${shop.push.apns.team-id:}") String teamId,
                           @Value("${shop.push.apns.key-id:}") String keyId,
                           @Value("${shop.push.apns.private-key:}") String privateKeyPem,
                           @Value("${shop.push.apns.topic:}") String topic) {
        require(teamId, "APNS_TEAM_ID");
        require(keyId, "APNS_KEY_ID");
        require(privateKeyPem, "APNS_PRIVATE_KEY");
        require(topic, "APNS_TOPIC");
        this.host = host;
        this.teamId = teamId;
        this.keyId = keyId;
        this.topic = topic;
        this.privateKey = PushCrypto.loadPkcs8(privateKeyPem, "EC");
        log.info("[push] APNs 通道已启用 topic={} host={}", topic, host);
    }

    private static void require(String v, String envName) {
        if (v == null || v.isBlank()) {
            throw new IllegalStateException(
                    "APNs 通道已开启（shop.push.apns.stub=false）但缺少配置：" + envName
                            + " —— 不配就推不出去，这里直接失败而不是退回桩");
        }
    }

    @Override
    public String provider() {
        return PushProvider.APNS;
    }

    @Override
    public SendResult push(String clientId, String title, String body, String link, String level) {
        boolean ring = LEVEL_RING.equals(level);
        /*
         * RING → apns-priority 10（立即送达）+ sound，把人叫回来；
         * NORMAL → priority 5（省电、可被系统择时），不响声。link 放顶层，端上读它路由。
         */
        String payload = "{\"aps\":{\"alert\":{\"title\":\"" + esc(title) + "\",\"body\":\"" + esc(body) + "\"}"
                + (ring ? ",\"sound\":\"default\"" : "")
                + "},\"link\":\"" + esc(link) + "\"}";

        HttpResponse<String> resp = send(clientId, payload, ring, token(false));
        if (resp.statusCode() == 403) {
            // ExpiredProviderToken / InvalidProviderToken：强刷一次再试
            resp = send(clientId, payload, ring, token(true));
        }
        if (resp.statusCode() != 200) {
            /*
             * 410 BadDeviceToken / Unregistered、400 参数错 —— cid 失效或请求错，不可重试。
             * 429/5xx 是 Apple 侧限流/故障，可重试。留痕后由站内信兜底。
             */
            int code = resp.statusCode();
            boolean retryable = code == 429 || code / 100 == 5;
            throw new PushException("APNs 拒绝：" + code + " " + strField(resp.body(), "reason"), retryable);
        }
        // apns-id 回执在响应头
        String apnsId = resp.headers().firstValue("apns-id").orElse(null);
        return SendResult.of(apnsId, ring ? "RING" : "NORMAL");
    }

    private HttpResponse<String> send(String deviceToken, String payload, boolean ring, String jwt) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(host + "/3/device/" + deviceToken))
                .header("authorization", "bearer " + jwt)
                .header("apns-topic", topic)
                .header("apns-push-type", "alert")
                .header("apns-priority", ring ? "10" : "5")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new PushException("APNs 接口网络失败：" + e.getMessage(), true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PushException("APNs 接口调用被中断", true);
        }
    }

    /**
     * ES256 provider token：header{alg=ES256,kid}, claim{iss=teamId,iat}。
     *
     * @param forceRefresh 收到 403 时置 true
     */
    private String token(boolean forceRefresh) {
        // 缓存 ~50min：Apple 要求 20~60min 内刷新，刷太勤会 429
        if (!forceRefresh && providerToken != null && System.currentTimeMillis() < tokenRefreshAt) {
            return providerToken;
        }
        synchronized (this) {
            if (!forceRefresh && providerToken != null && System.currentTimeMillis() < tokenRefreshAt) {
                return providerToken;
            }
            long now = System.currentTimeMillis() / 1000;
            String header = PushCrypto.base64Url("{\"alg\":\"ES256\",\"kid\":\"" + keyId + "\"}");
            String claim = PushCrypto.base64Url("{\"iss\":\"" + teamId + "\",\"iat\":" + now + "}");
            String signingInput = header + "." + claim;
            providerToken = signingInput + "." + PushCrypto.signEs256(signingInput, privateKey);
            tokenRefreshAt = System.currentTimeMillis() + 50 * 60_000L;
            return providerToken;
        }
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String strField(String json, String name) {
        Matcher m = Pattern.compile(STR_FIELD.formatted(name)).matcher(json == null ? "" : json);
        return m.find() ? m.group(1) : null;
    }
}
