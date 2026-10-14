package ai.neargo.shop.channel.notify.port;

import ai.neargo.shop.spi.user.WxAcodePort;
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
 * 微信小程序码（{@code wxacode.getUnlimited}）。
 *
 * <p><b>开关独立于登录与订阅消息</b>（{@code shop.wx.acode.stub}）：三条通道的接入前置不同 ——
 * 登录只要 appid+secret，订阅消息还要 mp 后台报备的模板号，本通道要的是
 * 「小程序至少有一个可用页面」。合成一个开关时，想先把码用起来会被订阅消息的
 * fail-fast 拦在启动阶段（那个坑 2026-08-17 已经踩过一次）。
 *
 * <p><b>两个必须知道的约束</b>：
 * <ol>
 *   <li>{@code getUnlimited} 生成的是<b>永久有效码</b>，每个 appid 总量有限（十万级）——
 *       所以调用方必须<b>一店一码、生成后落库复用</b>，不能每次请求都来一趟</li>
 *   <li>小程序<b>还没发布过任何版本</b>时必须传 {@code check_path:false}，
 *       否则微信会校验 {@code page} 是否存在于已发布版本并直接拒绝</li>
 * </ol>
 *
 * <p><b>access_token 自己管一份</b>：{@link ai.neargo.shop.spi.notify.WxSubscribePort}
 * 那边也有一份，但它只在订阅消息开启时才存在（要模板号），而本通道不该被它的前置卡住。
 * 用的同样是 {@code stable_token} —— 老的 {@code cgi-bin/token} 每次签发都会挤掉上一个，
 * 多实例部署时两个实例互踢，表现为「随机的 40001」。两份缓存拿到的是同一个 token，无害。
 */
@Component("wxAcodeGateway")
@ConditionalOnProperty(name = "shop.wx.acode.stub", havingValue = "false")
public class WxAcodeGateway implements WxAcodePort {

    private static final Logger log = LoggerFactory.getLogger(WxAcodeGateway.class);
    private static final Pattern STR = Pattern.compile("\"%s\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern NUM = Pattern.compile("\"%s\"\\s*:\\s*(-?\\d+)");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private final String host;
    private final String appid;
    private final String secret;
    /** 小程序还没发布时必须为 false，否则微信校验 page 是否存在于已发布版本 */
    private final boolean checkPath;

    private volatile String token;
    private volatile long tokenExpireAt;

    public WxAcodeGateway(@Value("${shop.wx.host:https://api.weixin.qq.com}") String host,
                          @Value("${shop.wx.appid:}") String appid,
                          @Value("${shop.wx.secret:}") String secret,
                          @Value("${shop.wx.acode.check-path:false}") boolean checkPath) {
        this.host = host;
        this.appid = appid;
        this.secret = secret;
        this.checkPath = checkPath;
        if (appid == null || appid.isBlank() || secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "小程序码已开启（shop.wx.acode.stub=false）但缺少 WX_APPID / WX_SECRET");
        }
        log.info("[wxacode] 小程序码通道已启用 appid={} checkPath={}", appid, checkPath);
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public byte[] unlimited(String scene, String page) {
        if (scene == null || scene.isBlank()) {
            return null;
        }
        /*
         * scene 微信限 32 字符且只接受有限字符集。超长不会报错 ——
         * 它会被截断，于是扫出来的码带着半截店铺码，落地页找不到店。
         */
        String s = scene.length() > 32 ? scene.substring(0, 32) : scene;
        byte[] png = request(s, page, accessToken(false));
        if (png == null) {
            // 40001 = token 失效（另一个实例刷过）。强制刷一次再试，只重试一次
            png = request(s, page, accessToken(true));
        }
        return png;
    }

    /** @return PNG 字节；微信回的是 JSON（错误）时返回 null */
    private byte[] request(String scene, String page, String accessToken) {
        String body = "{\"scene\":\"" + esc(scene) + "\""
                + (page == null || page.isBlank() ? "" : ",\"page\":\"" + esc(page) + "\"")
                + ",\"check_path\":" + checkPath
                + ",\"env_version\":\"release\"}";
        try {
            HttpResponse<byte[]> resp = http.send(
                    HttpRequest.newBuilder(URI.create(host + "/wxa/getwxacodeunlimit?access_token=" + accessToken))
                            .timeout(Duration.ofSeconds(10))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            byte[] out = resp.body();
            /*
             * **成功回的是图片字节，失败回的是 JSON** —— 同一个 200。
             * 不分辨的话，一段 JSON 会被当成 PNG 存进库、发到端上，
             * 而端上看到的是一张永远加载不出来的图，没有任何报错。
             */
            if (out != null && out.length > 0 && out[0] == '{') {
                String err = new String(out, StandardCharsets.UTF_8);
                log.warn("[wxacode] 生成失败: {} {}", intOf(err, "errcode"), strOf(err, "errmsg"));
                return null;
            }
            return out;
        } catch (java.io.IOException e) {
            log.warn("[wxacode] 网络失败: {}", e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
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
                    // 常规刷新**不要传 force_refresh** —— 传了就退化成老 token 接口的互踢行为
                    + (forceRefresh ? ",\"force_refresh\":true" : "") + "}";
            String resp = postJson("/cgi-bin/stable_token", body);
            String t = strOf(resp, "access_token");
            if (t == null || t.isBlank()) {
                throw new IllegalStateException("取 access_token 失败：" + resp);
            }
            token = t;
            // 到期前 5 分钟就换新，避免拿着一个正好过期的 token 去请求
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
