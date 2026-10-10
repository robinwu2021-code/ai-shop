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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Google FCM HTTP v1（海外 Android，设计 · 需求 2 · P3）。2026-08 核对自官方文档。
 *
 * <p><b>不引 firebase-admin SDK</b>：同 {@link GetuiPushGateway} 的离线构建约束。
 * 用到的只有 OAuth2 服务账号断言（RS256 JWT，见 {@link PushCrypto}）+ 一个 messages:send。
 *
 * <p><b>access_token 缓存</b>：Google 的 token 有效期 1h，且换 token 本身要走一次
 * OAuth2 往返。每推一条换一次会拖慢并可能撞限流，这里缓存到过期前 5 分钟。
 *
 * <p>默认不启用（{@code shop.push.fcm.stub} 默认 true → 本 bean 不创建，FCM 设备回落桩，
 * 见 {@code PushRouter}）。开启需 {@code FCM_PROJECT_ID/FCM_CLIENT_EMAIL/FCM_PRIVATE_KEY}。
 */
@Component
@ConditionalOnProperty(name = "shop.push.fcm.stub", havingValue = "false")
public class FcmPushGateway implements PushGateway {

    private static final Logger log = LoggerFactory.getLogger(FcmPushGateway.class);

    private static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
    private static final String STR_FIELD = "\"%s\"\\s*:\\s*\"([^\"]*)\"";
    private static final String NUM_FIELD = "\"%s\"\\s*:\\s*(-?\\d+)";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private final String projectId;
    private final String clientEmail;
    private final String tokenUri;
    private final PrivateKey privateKey;

    private volatile String accessToken;
    private volatile long tokenExpireAt;

    public FcmPushGateway(@Value("${shop.push.fcm.project-id:}") String projectId,
                          @Value("${shop.push.fcm.client-email:}") String clientEmail,
                          @Value("${shop.push.fcm.private-key:}") String privateKeyPem,
                          @Value("${shop.push.fcm.token-uri:https://oauth2.googleapis.com/token}")
                          String tokenUri) {
        require(projectId, "FCM_PROJECT_ID");
        require(clientEmail, "FCM_CLIENT_EMAIL");
        require(privateKeyPem, "FCM_PRIVATE_KEY");
        this.projectId = projectId;
        this.clientEmail = clientEmail;
        this.tokenUri = tokenUri;
        this.privateKey = PushCrypto.loadPkcs8(privateKeyPem, "RSA");
        log.info("[push] FCM 通道已启用 projectId={}", projectId);
    }

    private static void require(String v, String envName) {
        if (v == null || v.isBlank()) {
            throw new IllegalStateException(
                    "FCM 通道已开启（shop.push.fcm.stub=false）但缺少配置：" + envName
                            + " —— 不配就推不出去，这里直接失败而不是退回桩");
        }
    }

    @Override
    public String provider() {
        return PushProvider.FCM;
    }

    @Override
    public SendResult push(String clientId, String title, String body, String link, String level) {
        boolean ring = LEVEL_RING.equals(level);
        /*
         * message.android.priority = high 才会在息屏/后台唤醒设备（对应 RING）；
         * normal 省电、可能被系统合并延迟。link 放 data，端上 onPushMessage 读它路由 ——
         * FCM 的 notification 点击不原生携带深链，靠 data 传。
         */
        String reqBody = "{\"message\":{\"token\":\"" + esc(clientId) + "\""
                + ",\"notification\":{\"title\":\"" + esc(title) + "\",\"body\":\"" + esc(body) + "\"}"
                + ",\"data\":{\"link\":\"" + esc(link) + "\"}"
                + ",\"android\":{\"priority\":\"" + (ring ? "high" : "normal") + "\"}}}";

        String url = "https://fcm.googleapis.com/v1/projects/" + projectId + "/messages:send";
        HttpResponse<String> resp = post(url, reqBody, token(false));
        if (resp.statusCode() == 401) {
            // token 失效：强刷一次再试，只试一次
            resp = post(url, reqBody, token(true));
        }
        if (resp.statusCode() / 100 != 2) {
            /*
             * 4xx 多为 cid 失效（UNREGISTERED）、参数错——重试也是同一个结果，当不可重试。
             * 5xx 是 Google 侧临时故障，可重试。留痕后由站内信兜底。
             */
            boolean retryable = resp.statusCode() / 100 == 5;
            throw new PushException("FCM 拒绝：" + resp.statusCode() + " "
                    + strField(resp.body(), "status"), retryable);
        }
        // 成功回 {"name":"projects/<id>/messages/<msgId>"}
        return SendResult.of(strField(resp.body(), "name"), ring ? "RING" : "NORMAL");
    }

    /**
     * 取 OAuth2 access_token。断言是一枚 RS256 JWT（iss=服务账号邮箱，aud=token_uri）。
     *
     * @param forceRefresh 收到 401 时置 true
     */
    private String token(boolean forceRefresh) {
        if (!forceRefresh && accessToken != null && System.currentTimeMillis() < tokenExpireAt) {
            return accessToken;
        }
        synchronized (this) {
            if (!forceRefresh && accessToken != null && System.currentTimeMillis() < tokenExpireAt) {
                return accessToken;
            }
            long now = System.currentTimeMillis() / 1000;
            String header = PushCrypto.base64Url("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");
            String claim = PushCrypto.base64Url("{\"iss\":\"" + clientEmail + "\",\"scope\":\"" + SCOPE
                    + "\",\"aud\":\"" + tokenUri + "\",\"iat\":" + now + ",\"exp\":" + (now + 3600) + "}");
            String signingInput = header + "." + claim;
            String assertion = signingInput + "." + PushCrypto.signRs256(signingInput, privateKey);

            String form = "grant_type=" + enc("urn:ietf:params:oauth:grant-type:jwt-bearer")
                    + "&assertion=" + enc(assertion);
            HttpResponse<String> resp = postForm(tokenUri, form);
            String t = strField(resp.body(), "access_token");
            if (resp.statusCode() / 100 != 2 || t == null || t.isBlank()) {
                throw new PushException("FCM 取 token 失败：" + resp.statusCode()
                        + " " + resp.body(), resp.statusCode() / 100 == 5);
            }
            int expiresIn = intField(resp.body(), "expires_in");
            accessToken = t;
            // 官方 3600s；提前 5min 换，避免拿着一个正好过期的去推
            tokenExpireAt = System.currentTimeMillis() + (Math.max(expiresIn, 600) - 300) * 1000L;
            return t;
        }
    }

    private HttpResponse<String> post(String url, String jsonBody, String bearer) {
        return send(HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Authorization", "Bearer " + bearer)
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8)));
    }

    private HttpResponse<String> postForm(String url, String form) {
        return send(HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8)));
    }

    private HttpResponse<String> send(HttpRequest.Builder req) {
        try {
            return http.send(req.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new PushException("FCM 接口网络失败：" + e.getMessage(), true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PushException("FCM 接口调用被中断", true);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

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
