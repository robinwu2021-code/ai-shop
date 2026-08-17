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
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 个推 RestAPI V2（uni-push 2.0 的底座，ADR-018）。2026-08 核对自官方文档。
 *
 * <p><b>不引官方 SDK</b>：本项目一律 {@code mvn -o} 离线构建（同 {@link AliSmsGateway}）。
 * 用到的只有两个接口（鉴权、单推 cid），签名是一次 SHA-256，JDK 自带的够用。
 *
 * <p><b>鉴权 token 缓存</b>：个推的 token 有效期 24h，且**接口本身有调用频率限制** ——
 * 每推一条就换一次 token 会先撞限流，表现为随机的推送失败。这里缓存到过期前 1 小时。
 *
 * <p><b>免费档的现实</b>（ADR-018）：在线消息（长连接）不限量，**厂商离线通道有日配额**。
 * 配额用尽时个推返回业务码，这里当**不可重试**抛出 —— 重试只会把日志刷满，
 * 而用户那边的结果是一样的：这条推送没到，站内信兜底。
 */
@Component
@ConditionalOnProperty(name = "shop.push.stub", havingValue = "false")
public class GetuiPushGateway implements PushGateway {

    private static final Logger log = LoggerFactory.getLogger(GetuiPushGateway.class);

    @Override
    public String provider() {
        return PushProvider.GETUI;
    }

    private static final String STR_FIELD = "\"%s\"\\s*:\\s*\"([^\"]*)\"";
    private static final String NUM_FIELD = "\"%s\"\\s*:\\s*(-?\\d+)";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private final String host;
    private final String appId;
    private final String appKey;
    private final String masterSecret;

    private volatile String token;
    private volatile long tokenExpireAt;

    public GetuiPushGateway(@Value("${shop.push.getui.host:https://restapi.getui.com}") String host,
                            @Value("${shop.push.getui.app-id:}") String appId,
                            @Value("${shop.push.getui.app-key:}") String appKey,
                            @Value("${shop.push.getui.master-secret:}") String masterSecret) {
        this.host = host;
        this.appId = appId;
        this.appKey = appKey;
        this.masterSecret = masterSecret;
        require(appId, "GETUI_APP_ID");
        require(appKey, "GETUI_APP_KEY");
        require(masterSecret, "GETUI_MASTER_SECRET");
        log.info("[push] 个推通道已启用 appId={}", appId);
    }

    private static void require(String v, String envName) {
        if (v == null || v.isBlank()) {
            throw new IllegalStateException(
                    "推送通道已开启（shop.push.stub=false）但缺少配置：" + envName
                            + " —— 不配就推不出去，这里直接失败而不是退回桩");
        }
    }

    @Override
    public SendResult push(String clientId, String title, String body, String link, String level) {
        boolean ring = LEVEL_RING.equals(level);
        /*
         * 三段结构（个推 V2 单推）：
         *   settings.strategy.default=1 —— 在线走个推长连接，离线转厂商通道
         *   push_message.notification  —— 通知栏消息（不是透传，App 没启动也要显示）
         *   click_type=payload         —— 点击把 payload 交回 App，由端上路由到 link
         *
         * channel_level=4 是 Android 8+ 的通知重要性「紧急」：横幅 + 声音。
         * 只给 RING 用 —— 每条都紧急等于没有紧急，用户会整个关掉通知。
         */
        String payload = "{\"link\":\"" + esc(link) + "\"}";
        String reqBody = "{\"request_id\":\"" + reqId(clientId, link, title)
                + "\",\"settings\":{\"ttl\":3600000,\"strategy\":{\"default\":1}"
                + (ring ? ",\"channel_level\":\"4\"" : "")
                + "},\"audience\":{\"cid\":[\"" + esc(clientId) + "\"]}"
                + ",\"push_message\":{\"notification\":{"
                + "\"title\":\"" + esc(title) + "\",\"body\":\"" + esc(body) + "\""
                + ",\"click_type\":\"payload\",\"payload\":\"" + esc(payload) + "\""
                + ",\"channel_id\":\"" + (ring ? "ring" : "normal") + "\""
                + ",\"channel_name\":\"" + (ring ? "订单提醒" : "通知") + "\""
                + "}}}";

        String resp = post("/v2/push/single/cid", reqBody, token(false));
        int code = intField(resp, "code");
        if (code == 10001 || code == 10003) {
            // token 失效/过期：强刷一次再试，只试一次
            resp = post("/v2/push/single/cid", reqBody, token(true));
            code = intField(resp, "code");
        }
        if (code != 0) {
            /*
             * 业务码不重试：cid 不存在（用户卸载/换设备）、离线配额用尽（免费档，ADR-018）、
             * 应用被限制 —— 重试一万次也是同一个结果。留痕后由站内信兜底。
             */
            throw new PushException("个推拒绝：" + code + " " + strField(resp, "msg"), false);
        }
        return SendResult.of(strField(resp, "task_id"), ring ? "RING" : "NORMAL");
    }

    /**
     * 鉴权。sign = SHA256(appkey + timestamp + mastersecret)。
     *
     * @param forceRefresh 收到 10001/10003 时置 true
     */
    private String token(boolean forceRefresh) {
        if (!forceRefresh && token != null && System.currentTimeMillis() < tokenExpireAt) {
            return token;
        }
        synchronized (this) {
            if (!forceRefresh && token != null && System.currentTimeMillis() < tokenExpireAt) {
                return token;
            }
            String ts = String.valueOf(System.currentTimeMillis());
            String sign = sha256(appKey + ts + masterSecret);
            String resp = post("/v2/auth",
                    "{\"sign\":\"" + sign + "\",\"timestamp\":\"" + ts
                            + "\",\"appkey\":\"" + esc(appKey) + "\"}", null);
            String t = strField(resp, "token");
            if (t == null || t.isBlank()) {
                throw new PushException("个推鉴权失败：" + intField(resp, "code")
                        + " " + strField(resp, "msg"), false);
            }
            token = t;
            // 官方 24h；提前 1h 换，避免拿着一个正好过期的 token 去推
            tokenExpireAt = System.currentTimeMillis() + 23 * 3600_000L;
            return t;
        }
    }

    /** 幂等键：同一设备 + 同一落点 + 同一标题在个推侧视为同一请求。长度要 10–32。 */
    private static String reqId(String clientId, String link, String title) {
        return sha256(clientId + "|" + link + "|" + title).substring(0, 32);
    }

    private String post(String path, String jsonBody, String authToken) {
        try {
            HttpRequest.Builder req = HttpRequest
                    .newBuilder(URI.create(host + "/" + appId + path))
                    .header("Content-Type", "application/json;charset=utf-8")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
            if (authToken != null) {
                req.header("token", authToken);
            }
            return http.send(req.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body();
        } catch (java.io.IOException e) {
            throw new PushException("个推接口网络失败：" + e.getMessage(), true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PushException("个推接口调用被中断", true);
        }
    }

    private static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** payload 里嵌了一层 JSON，转义要能承受两次编码。 */
    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String strField(String json, String name) {
        Matcher m = Pattern.compile(STR_FIELD.formatted(name)).matcher(json == null ? "" : json);
        return m.find() ? m.group(1) : null;
    }

    private static int intField(String json, String name) {
        Matcher m = Pattern.compile(NUM_FIELD.formatted(name)).matcher(json == null ? "" : json);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }
}
