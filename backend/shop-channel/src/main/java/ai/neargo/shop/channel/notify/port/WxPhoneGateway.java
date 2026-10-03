package ai.neargo.shop.channel.notify.port;

import ai.neargo.shop.spi.user.WxPhonePort;
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
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 微信手机号快速验证（{@code phonenumber.getPhoneNumber}）。
 *
 * <p><b>开关独立于登录 / 订阅消息 / 小程序码</b>（{@code shop.wx.phone.stub}）：
 * 四条通道的接入前置各不相同 —— 登录只要 appid+secret，订阅消息还要模板号，
 * 小程序码要「至少有一个可用页面」，而本通道要的是<b>已认证 + 非个人主体</b>，
 * 并且**按次计费**。合成一个开关，想先把便宜的用起来会被贵的拦在启动阶段。
 *
 * <p>token 与小程序码通道各管一份 {@code stable_token}：拿到的是同一个 token，无害；
 * 而共用一份会让两条通道的可用性绑在一起，那正是上面要避免的。
 */
@Component("wxPhoneGateway")
@ConditionalOnProperty(name = "shop.wx.phone.stub", havingValue = "false")
public class WxPhoneGateway implements WxPhonePort {

    private static final Logger log = LoggerFactory.getLogger(WxPhoneGateway.class);
    private static final Pattern STR = Pattern.compile("\"%s\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern NUM = Pattern.compile("\"%s\"\\s*:\\s*(-?\\d+)");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private final String host;
    private final String appid;
    private final String secret;

    private volatile String token;
    private volatile long tokenExpireAt;

    public WxPhoneGateway(@Value("${shop.wx.host:https://api.weixin.qq.com}") String host,
                          @Value("${shop.wx.appid:}") String appid,
                          @Value("${shop.wx.secret:}") String secret) {
        this.host = host;
        this.appid = appid;
        this.secret = secret;
        if (appid == null || appid.isBlank() || secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "手机号快速验证已开启（shop.wx.phone.stub=false）但缺少 WX_APPID / WX_SECRET");
        }
        log.info("[wxphone] 手机号快速验证通道已启用 appid={}", appid);
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public String phoneOf(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String phone = request(code, accessToken(false));
        if (phone == null) {
            // 40001 = token 失效（另一个实例刷过）。强制刷一次再试，只重试一次
            phone = request(code, accessToken(true));
        }
        return phone;
    }

    /** @return 手机号；失败返回 null（**不返回占位号**，理由见 StubWxPhoneGateway） */
    private String request(String code, String accessToken) {
        String body = "{\"code\":\"" + esc(code) + "\"}";
        String resp = postJson("/wxa/business/getuserphonenumber?access_token=" + accessToken, body);
        int errcode = intOf(resp, "errcode");
        if (errcode != 0) {
            log.warn("[wxphone] 换手机号失败: {} {}", errcode, strOf(resp, "errmsg"));
            return null;
        }
        /*
         * 取 `purePhoneNumber`（不含国家码）而不是 `phoneNumber`：
         * 库里与短信通道用的都是 11 位号，混进 `+86` 前缀会让
         * 「同一个人」在 usr_identity 里变成两条凭证 —— 而那不报错，只是他有了两个账号。
         */
        String pure = strOf(resp, "purePhoneNumber");
        return pure == null || pure.isBlank() ? null : pure;
    }

    private String accessToken(boolean forceRefresh) {
        if (!forceRefresh && token != null && System.currentTimeMillis() < tokenExpireAt) {
            return token;
        }
        synchronized (this) {
            if (!forceRefresh && token != null && System.currentTimeMillis() < tokenExpireAt) {
                return token;
            }
            String body = "{\"grant_type\":\"client_credential\",\"appid\":\"" + esc(appid)
                    + "\",\"secret\":\"" + esc(secret) + "\""
                    + (forceRefresh ? ",\"force_refresh\":true" : "") + "}";
            String resp = postJson("/cgi-bin/stable_token", body);
            String t = strOf(resp, "access_token");
            if (t == null || t.isBlank()) {
                throw new IllegalStateException("取 access_token 失败：" + resp);
            }
            token = t;
            tokenExpireAt = System.currentTimeMillis() + (intOf(resp, "expires_in") - 300) * 1000L;
            return token;
        }
    }

    private String postJson(String path, String body) {
        try {
            return http.send(HttpRequest.newBuilder(URI.create(host + path))
                            .timeout(Duration.ofSeconds(10))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("微信接口网络失败：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("微信接口调用被中断", e);
        }
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String strOf(String json, String name) {
        Matcher m = Pattern.compile(STR.pattern().formatted(name)).matcher(json == null ? "" : json);
        return m.find() ? m.group(1) : null;
    }

    private static int intOf(String json, String name) {
        Matcher m = Pattern.compile(NUM.pattern().formatted(name)).matcher(json == null ? "" : json);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }
}
