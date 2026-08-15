package ai.neargo.shop.channel.notify.port;

import ai.neargo.shop.spi.notify.SendResult;
import ai.neargo.shop.spi.notify.WxSubscribePort;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 微信小程序订阅消息（{@code subscribeMessage.send}）。2026-08 核对自官方文档。
 *
 * <p><b>不引官方 SDK</b>：本项目一律 {@code mvn -o} 离线构建（同 {@link AliSmsGateway}）。
 * 订阅消息只有两个接口（取 token、发消息），请求与响应都是平铺 JSON，JDK 自带的够用。
 *
 * <p><b>access_token 用 {@code stable_token}</b> 而不是老的 {@code cgi-bin/token}：
 * 老接口每次调用都签发新 token 并挤掉旧的 —— 多实例部署时两个实例会互相踢对方的 token，
 * 表现为「随机的 40001」，极难排查。stable_token 在有效期内返回同一个，天然多实例安全。
 *
 * <p><b>模板字段名写死在本类</b>（{@code thing/number/amount…}）：它们是 mp 后台报备
 * 模板时定下的通道概念，与阿里云短信的模板参数同理 —— 换模板改这里，不该惊动领域代码。
 *
 * <p><b>启动即校验凭据</b>：缺 appid/secret/模板号时直接起不来，不静默退回桩（同短信通道，
 * 静默退回的表现是「已发送」日志照常出现而用户一条都收不到）。
 */
@Component("wxSubscribeGateway")
@ConditionalOnProperty(name = "shop.wx.subscribe.stub", havingValue = "false")
public class WxSubscribeGateway implements WxSubscribePort {

    private static final Logger log = LoggerFactory.getLogger(WxSubscribeGateway.class);

    private static final String STR_FIELD = "\"%s\"\\s*:\\s*\"([^\"]*)\"";
    private static final String NUM_FIELD = "\"%s\"\\s*:\\s*(-?\\d+)";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private final String host;
    private final String appid;
    private final String secret;
    private final String tplOrderArrived;
    private final String tplRefunded;
    /** {@code developer} / {@code trial} / {@code formal}。联调时切 trial 免得打扰真实用户。 */
    private final String mpState;

    /** stable_token 缓存。到期前 5 分钟就换新，避免拿着一个正好过期的 token 去发。 */
    private volatile String token;
    private volatile long tokenExpireAt;

    public WxSubscribeGateway(@Value("${shop.wx.host:https://api.weixin.qq.com}") String host,
                              @Value("${shop.wx.appid:}") String appid,
                              @Value("${shop.wx.secret:}") String secret,
                              @Value("${shop.wx.templates.order-arrived:}") String tplOrderArrived,
                              @Value("${shop.wx.templates.refunded:}") String tplRefunded,
                              @Value("${shop.wx.mp-state:formal}") String mpState,
                              @Value("${shop.wx.login.stub:true}") boolean loginStub) {
        this.host = host;
        this.appid = appid;
        this.secret = secret;
        this.tplOrderArrived = tplOrderArrived;
        this.tplRefunded = tplRefunded;
        this.mpState = mpState;
        /*
         * 两条通道的开关拆开之后，出现了一个此前不可能存在的组合：登录走桩、订阅消息真发。
         * 那意味着库里存的是**假 openid**（桩把 wx.login 的 code 直接当 openid），
         * 而这里拿着它去调 subscribeMessage.send —— 每一条都会以 40003 失败，
         * 且失败发生在异步发送里，日志上看是「发过了」。
         *
         * 拆开关的收益只在「登录先真、订阅消息后真」这一个方向上，反方向没有任何用途，
         * 所以不留给人去记，直接在启动时拒绝。
         */
        if (loginStub) {
            throw new IllegalStateException(
                    "订阅消息已开启（shop.wx.subscribe.stub=false）但登录还是桩（shop.wx.login.stub=true）"
                            + " —— 库里是假 openid，发出去每条都是 40003。要真发就先把登录也切真");
        }
        require(appid, "WX_APPID");
        require(secret, "WX_SECRET");
        require(tplOrderArrived, "WX_TPL_ORDER_ARRIVED（mp 后台报备的到货通知模板号）");
        require(tplRefunded, "WX_TPL_REFUNDED（mp 后台报备的退款通知模板号）");
        log.info("[wxsub] 订阅消息通道已启用 appid={} state={}", appid, mpState);
    }

    private static void require(String v, String envName) {
        if (v == null || v.isBlank()) {
            throw new IllegalStateException(
                    "订阅消息通道已开启（shop.wx.subscribe.stub=false）但缺少配置：" + envName
                            + " —— 不配就发不出去，这里直接失败而不是退回桩");
        }
    }

    /**
     * 场景 → 环境变量里配的模板号。
     *
     * <p><b>运营的覆盖不在这里读</b>：那是领域配置，由 message 域的装饰器
     * （{@code NotifyLoggingWxSubscribePort}）叠在上面 —— 通道只认自己那份配置，
     * 不碰数据库。这样「运营改了模板号」在走桩时同样生效，
     * 而不是只有接了真通道才看得出来。
     */
    @Override
    public String templateId(String scene) {
        return switch (scene) {
            case SCENE_ORDER_ARRIVED -> tplOrderArrived;
            case SCENE_REFUNDED -> tplRefunded;
            default -> null;
        };
    }

    @Override
    public SendResult sendOrderArrived(String openId, int orderCount, String page, String tip) {
        // 字段名与报备模板一致：number1=到货件数 thing2=提示语
        Map<String, String> data = new LinkedHashMap<>();
        data.put("number1", String.valueOf(orderCount));
        // thing 类字段微信限 20 字，超了整条会被拒 —— 在这里截断，
        // 让「填长了」表现为少几个字，而不是整条发不出去
        data.put("thing2", clamp(tip == null || tip.isBlank()
                ? "包裹已到自提点，请凭取货码取货" : tip, 20));
        return send(openId, tplOrderArrived, page, data);
    }

    @Override
    public SendResult sendRefunded(String openId, String amountText, String page, String tip) {
        // amount1=退款金额 thing2=提示语。截断口径与到货那条一致（见 sendOrderArrived）
        Map<String, String> data = new LinkedHashMap<>();
        data.put("amount1", amountText);
        data.put("thing2", clamp(tip == null || tip.isBlank()
                ? "退款将原路退回，到账以支付渠道为准" : tip, 20));
        return send(openId, tplRefunded, page, data);
    }

    private SendResult send(String openId, String templateId, String page, Map<String, String> data) {
        StringBuilder body = new StringBuilder()
                .append("{\"touser\":\"").append(esc(openId))
                .append("\",\"template_id\":\"").append(esc(templateId))
                .append("\",\"miniprogram_state\":\"").append(esc(mpState)).append('"');
        if (page != null && !page.isBlank()) {
            body.append(",\"page\":\"").append(esc(page)).append('"');
        }
        body.append(",\"data\":{");
        boolean first = true;
        for (var e : data.entrySet()) {
            if (!first) {
                body.append(',');
            }
            first = false;
            body.append('"').append(esc(e.getKey())).append("\":{\"value\":\"")
                    .append(esc(e.getValue())).append("\"}");
        }
        body.append("}}");

        String resp = post("/cgi-bin/message/subscribe/send?access_token=" + accessToken(false),
                body.toString());
        int code = intField(resp, "errcode");
        if (code == 40001 || code == 42001) {
            // token 失效（后台改过 secret、或本地缓存跨过了有效期）：强刷一次再试，只试一次
            resp = post("/cgi-bin/message/subscribe/send?access_token=" + accessToken(true),
                    body.toString());
            code = intField(resp, "errcode");
        }
        if (code != 0) {
            /*
             * 43101 = 用户未订阅或额度已用完。领域侧扣过额度才会走到这里，出现即说明
             * 两边的账对不上（比如用户在微信设置里关了通知）—— 记下来但不重试。
             */
            throw new WxSubscribeException(
                    "微信拒绝：" + code + " " + strField(resp, "errmsg"), false);
        }
        return SendResult.of(strField(resp, "msgid"), templateId);
    }

    /**
     * @param forceRefresh 收到 40001 时置 true。stable_token 本身有 force_refresh 参数，
     *                     但常规刷新**不要传** —— 传了就退化成老 token 接口的互踢行为
     */
    private String accessToken(boolean forceRefresh) {
        if (!forceRefresh && token != null && System.currentTimeMillis() < tokenExpireAt) {
            return token;
        }
        synchronized (this) {
            if (!forceRefresh && token != null && System.currentTimeMillis() < tokenExpireAt) {
                return token;
            }
            String resp = post("/cgi-bin/stable_token",
                    "{\"grant_type\":\"client_credential\",\"appid\":\"" + esc(appid)
                            + "\",\"secret\":\"" + esc(secret) + "\""
                            + (forceRefresh ? ",\"force_refresh\":true" : "") + "}");
            String t = strField(resp, "access_token");
            if (t == null || t.isBlank()) {
                throw new WxSubscribeException(
                        "取 access_token 失败：" + intField(resp, "errcode") + " "
                                + strField(resp, "errmsg"), false);
            }
            long expiresIn = Math.max(60, intField(resp, "expires_in"));
            token = t;
            tokenExpireAt = System.currentTimeMillis() + (expiresIn - 300) * 1000L;
            return t;
        }
    }

    private String post(String path, String jsonBody) {
        try {
            HttpResponse<String> resp = http.send(HttpRequest.newBuilder(URI.create(host + path))
                            .header("Content-Type", "application/json")
                            .timeout(Duration.ofSeconds(10))
                            .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return resp.body();
        } catch (java.io.IOException e) {
            throw new WxSubscribeException("微信接口网络失败：" + e.getMessage(), true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WxSubscribeException("微信接口调用被中断", true);
        }
    }

    /** 微信 {@code thing} 字段限 20 字。超长整条被拒，所以宁可少几个字。 */
    private static String clamp(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 只处理 JSON 字符串里必须转义的两个字符；换行等控制字符不会出现在这些字段里。 */
    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String strField(String json, String name) {
        Matcher m = Pattern.compile(STR_FIELD.formatted(name)).matcher(json == null ? "" : json);
        return m.find() ? m.group(1) : null;
    }

    /** 字段缺失返回 0 —— 微信成功响应里往往不带 errcode，缺失即成功。 */
    private static int intField(String json, String name) {
        Matcher m = Pattern.compile(NUM_FIELD.formatted(name)).matcher(json == null ? "" : json);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }
}
