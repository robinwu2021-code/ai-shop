package ai.neargo.shop.channel.notify.port;

import ai.neargo.shop.spi.notify.WxUrlLinkPort;
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
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 微信小程序 URL Link 的真通道（{@code urllink.generate}）。
 *
 * <p>开关独立（{@code shop.wx.urllink.stub=false}），理由同 {@code WxShippingGateway} 的类注释：
 * 合成一个总开关会让「先把这条接通」被别的 fail-fast 拦在启动阶段。
 *
 * <p>access_token 自己管一份（用 {@code stable_token}，不互踢）——与 {@code WxAcodeGateway}
 * 同一套做法。失败一律返回空（不抛）：URL Link 是增强，断了退回 H5，不该拖垮发货短信。
 */
@Component("wxUrlLinkGateway")
@ConditionalOnProperty(name = "shop.wx.urllink.stub", havingValue = "false")
public class WxUrlLinkGateway implements WxUrlLinkPort {

    private static final Logger log = LoggerFactory.getLogger(WxUrlLinkGateway.class);
    private static final Pattern LINK = Pattern.compile("\"url_link\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern TOKEN = Pattern.compile("\"access_token\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern EXPIRES = Pattern.compile("\"expires_in\"\\s*:\\s*(\\d+)");
    private static final Pattern ERRCODE = Pattern.compile("\"errcode\"\\s*:\\s*(-?\\d+)");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private final String host;
    private final String appid;
    private final String secret;
    /** URL Link 有效期（天），到期后链接失效。默认 30，与看件令牌同寿 */
    private final int expireDays;

    private volatile String token;
    private volatile long tokenExpireAt;

    public WxUrlLinkGateway(@Value("${shop.wx.host:https://api.weixin.qq.com}") String host,
                            @Value("${shop.wx.appid:}") String appid,
                            @Value("${shop.wx.secret:}") String secret,
                            @Value("${shop.wx.urllink.expire-days:30}") int expireDays) {
        this.host = host;
        this.appid = appid;
        this.secret = secret;
        this.expireDays = expireDays > 0 && expireDays <= 30 ? expireDays : 30;
        if (appid == null || appid.isBlank() || secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "URL Link 已开启（shop.wx.urllink.stub=false）但缺少 WX_APPID / WX_SECRET");
        }
    }

    @Override
    public Optional<String> generate(String path, String query) {
        String at = accessToken();
        if (at == null) {
            return Optional.empty();
        }
        // expire_interval 单位是天，is_expire=true 时必填
        String body = "{\"path\":\"" + esc(path) + "\",\"query\":\"" + esc(query)
                + "\",\"is_expire\":true,\"expire_type\":1,\"expire_interval\":" + expireDays + "}";
        try {
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(URI.create(host + "/wxa/generate_urllink?access_token=" + at))
                            .timeout(Duration.ofSeconds(10))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String link = group(LINK, resp.body());
            if (link == null || link.isBlank()) {
                log.warn("[wx-urllink] 生成失败，退回 H5：{}", resp.body());
                return Optional.empty();
            }
            return Optional.of(link);
        } catch (java.io.IOException e) {
            log.warn("[wx-urllink] 网络失败，退回 H5：{}", e.getMessage());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private String accessToken() {
        if (token != null && System.currentTimeMillis() < tokenExpireAt) {
            return token;
        }
        synchronized (this) {
            if (token != null && System.currentTimeMillis() < tokenExpireAt) {
                return token;
            }
            String body = "{\"grant_type\":\"client_credential\",\"appid\":\"" + esc(appid)
                    + "\",\"secret\":\"" + esc(secret) + "\"}";
            try {
                HttpResponse<String> resp = http.send(
                        HttpRequest.newBuilder(URI.create(host + "/cgi-bin/stable_token"))
                                .timeout(Duration.ofSeconds(10))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                                .build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                String t = group(TOKEN, resp.body());
                if (t == null || t.isBlank()) {
                    log.warn("[wx-urllink] 取 access_token 失败：{}", resp.body());
                    return null;
                }
                token = t;
                String exp = group(EXPIRES, resp.body());
                long secs = exp == null ? 7200 : Long.parseLong(exp);
                tokenExpireAt = System.currentTimeMillis() + (secs - 300) * 1000L;
                return token;
            } catch (java.io.IOException e) {
                log.warn("[wx-urllink] 取 access_token 网络失败：{}", e.getMessage());
                return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    private static String group(Pattern p, String s) {
        if (s == null) {
            return null;
        }
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
