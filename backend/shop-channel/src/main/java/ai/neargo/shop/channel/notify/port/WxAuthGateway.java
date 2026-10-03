package ai.neargo.shop.channel.notify.port;

import ai.neargo.shop.spi.user.WxAuthPort;
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
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 微信 {@code jscode2session}。与 {@link ai.neargo.shop.notify.port.WxSubscribeGateway} 共用 {@code shop.wx.appid/secret}，
 * 但**开关是分开的**（{@code shop.wx.login.stub}）：本通道只要 appid + secret 就能通，
 * 订阅消息还要 mp 后台报备过的模板号 —— 接入前置不同，合成一个开关会让
 * 「先把登录接通」被订阅消息的 fail-fast 拦在启动阶段。
 *
 * <p>反过来那一半仍然不许：登录走桩而订阅消息真发，会造出「库里是假 openid、
 * 通道却在真发」的缝合世界。那个组合由 {@link ai.neargo.shop.notify.port.WxSubscribeGateway} 的构造器拒绝。
 *
 * <p>{@code session_key} <b>刻意不返回也不落库</b>：它是解密用户敏感数据的密钥，
 * 当前登录链路用不到；存一个用不到的密钥只是多一处泄露面。
 */
@Component("wxAuthGateway")
@ConditionalOnProperty(name = "shop.wx.login.stub", havingValue = "false")
public class WxAuthGateway implements WxAuthPort {

    private static final Logger log = LoggerFactory.getLogger(WxAuthGateway.class);

    private static final String STR_FIELD = "\"%s\"\\s*:\\s*\"([^\"]*)\"";
    private static final String NUM_FIELD = "\"%s\"\\s*:\\s*(-?\\d+)";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private final String host;
    private final String appid;
    private final String secret;

    public WxAuthGateway(@Value("${shop.wx.host:https://api.weixin.qq.com}") String host,
                         @Value("${shop.wx.appid:}") String appid,
                         @Value("${shop.wx.secret:}") String secret) {
        this.host = host;
        this.appid = appid;
        this.secret = secret;
        if (appid == null || appid.isBlank() || secret == null || secret.isBlank()) {
            throw new IllegalStateException("微信登录已开启（shop.wx.login.stub=false）但缺少 WX_APPID / WX_SECRET");
        }
        log.info("[wxauth] code2Session 已启用 appid={}", appid);
    }

    @Override
    public WxSession codeToSession(String jsCode) {
        String url = host + "/sns/jscode2session?appid=" + enc(appid) + "&secret=" + enc(secret)
                + "&js_code=" + enc(jsCode) + "&grant_type=authorization_code";
        String resp;
        try {
            resp = http.send(HttpRequest.newBuilder(URI.create(url))
                                    .timeout(Duration.ofSeconds(10)).GET().build(),
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                    .body();
        } catch (java.io.IOException e) {
            throw new WxAuthException("微信登录接口网络失败：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WxAuthException("微信登录接口调用被中断");
        }

        String openId = strField(resp, "openid");
        if (openId == null || openId.isBlank()) {
            // 40029=code 无效 40163=code 已被用过 —— 都是端上重复提交同一个 code 的形状
            throw new WxAuthException("code2Session 失败：" + intField(resp, "errcode")
                    + " " + strField(resp, "errmsg"));
        }
        String unionId = strField(resp, "unionid");
        return new WxSession(openId, unionId == null || unionId.isBlank() ? null : unionId);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
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
