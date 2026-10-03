package ai.neargo.shop.channel.notify.port;

import ai.neargo.shop.spi.trade.WxShippingPort;
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
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 微信小程序发货信息录入的真通道。
 *
 * <p><b>开关独立</b>（{@code shop.wx.shipping.stub=false}）：与登录、订阅消息、
 * 小程序码各自的接入前置不同，合成一个开关会让「先把发货接通」被别的
 * fail-fast 拦在启动阶段。这条规矩在 {@code WxPhoneGateway} 的类注释里已经写过。
 */
@Component("wxShippingGateway")
@ConditionalOnProperty(name = "shop.wx.shipping.stub", havingValue = "false")
public class WxShippingGateway implements WxShippingPort {

    private static final Logger log = LoggerFactory.getLogger(WxShippingGateway.class);
    private static final Pattern ERRCODE = Pattern.compile("\"errcode\"\\s*:\\s*(-?\\d+)");
    private static final Pattern ERRMSG = Pattern.compile("\"errmsg\"\\s*:\\s*\"([^\"]*)\"");

    /**
     * <b>「订单已发货」当成功</b>。重试撞到它是正常的 —— 上一次其实报成功了，
     * 只是回执没收到。按失败重试的话这笔单会永远重试下去，
     * 而台账上它永远是「失败」，运营永远在看一条修不好的告警。
     */
    private static final int ALREADY_SHIPPED = 10060002;
    /** 这个小程序还没开通「发货信息录入」。**不可重试**，也不该当成缺陷去查代码 */
    private static final int NOT_TRADE_MANAGED = 10060011;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private final String host;
    private final String appid;
    private final String secret;
    private final String mchId;

    private volatile String token;
    private volatile long tokenExpireAt;

    public WxShippingGateway(@Value("${shop.wx.host:https://api.weixin.qq.com}") String host,
                             @Value("${shop.wx.appid:}") String appid,
                             @Value("${shop.wx.secret:}") String secret,
                             @Value("${shop.pay.wechat.mchid:}") String mchId) {
        this.host = host;
        this.appid = appid;
        this.secret = secret;
        this.mchId = mchId;
        if (appid == null || appid.isBlank() || secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "发货信息录入已开启（shop.wx.shipping.stub=false）但缺少 WX_APPID / WX_SECRET");
        }
        if (mchId == null || mchId.isBlank()) {
            /*
             * order_key 用「商户单号」定位，缺商户号就定位不到那笔单 ——
             * 而微信的回复会是一个看起来像参数错的码，查半天查不到根上。
             */
            throw new IllegalStateException(
                    "发货信息录入已开启但缺少 shop.pay.wechat.mchid —— 上报要靠它定位支付单");
        }
        log.info("[wxship] 发货信息录入通道已启用 appid={} mchid={}", appid, mchId);
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public Result upload(Command cmd) {
        String bad = validate(cmd);
        if (bad != null) {
            // 缺件不发：微信会回一个参数码，而那个码不会告诉你缺的是哪一个
            return Result.fatal(-1, bad);
        }
        String body = buildBody(cmd);
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(host + "/wxa/sec/order/upload_shipping_info?access_token=" + accessToken()))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            String resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body();
            int code = intOf(resp, ERRCODE, -1);
            String msg = strOf(resp, ERRMSG);

            if (code == 0 || code == ALREADY_SHIPPED) {
                log.info("[wxship] 上报成功 outTradeNo={}{}", cmd.outTradeNo(),
                        code == ALREADY_SHIPPED ? "（微信说已发货，按成功处理）" : "");
                return Result.ok();
            }
            if (code == NOT_TRADE_MANAGED) {
                /*
                 * 这个号还没开通发货信息录入。**不是代码缺陷**，
                 * 说清楚免得有人去查上报逻辑 —— 那里没有问题。
                 */
                log.error("[wxship] 小程序未开通「发货信息录入」（{}）—— "
                        + "去小程序后台开通，代码这边没有问题。outTradeNo={}", code, cmd.outTradeNo());
                return Result.fatal(code, "小程序未开通发货信息录入：" + msg);
            }
            /*
             * access_token 过期（40001/42001）值得重试：下一次会重新取。
             * 其余按不可重试处理 —— 参数错重试一万次也是错，
             * 而不可重试的失败一直占着重试队列，真正该人工看的单就没人看。
             */
            boolean retryable = code == 40001 || code == 42001 || code == 45009;
            if (retryable) {
                token = null;   // 让下一次重新取
            }
            log.warn("[wxship] 上报失败 outTradeNo={} errcode={} errmsg={} 可重试={}",
                    cmd.outTradeNo(), code, msg, retryable);
            return retryable ? Result.retry(code, msg) : Result.fatal(code, msg);
        } catch (Exception e) {
            // 网络层失败一律可重试：这一刻「到底报没报上」是真的不知道，
            // 而微信对重复上报会回 10060002，我们把它当成功——所以重试是安全的
            log.warn("[wxship] 上报异常 outTradeNo={}：{}", cmd.outTradeNo(), e.toString());
            return Result.retry(-1, e.getClass().getSimpleName());
        }
    }

    /** 缺哪一件说哪一件。微信的参数码不会告诉你缺的是哪一个字段 */
    private static String validate(Command c) {
        if (c.outTradeNo() == null || c.outTradeNo().isBlank()) {
            return "缺商户单号";
        }
        if (c.itemDesc() == null || c.itemDesc().isBlank()) {
            return "缺商品描述（微信 10060008：不能为空）";
        }
        if (c.payerOpenid() == null || c.payerOpenid().isBlank()) {
            return "缺付款人 openid —— 必须是支付时那个 AppID 下的，从支付单读";
        }
        if (c.logisticsType() == 1
                && (blank(c.trackingNo()) || blank(c.expressCompany()))) {
            // 快递必须运单号与快递公司**成对**，缺一个微信回 268485226/227
            return "快递发货缺运单号或快递公司（两者必须成对）";
        }
        return null;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private String buildBody(Command c) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"order_key\":{\"order_number_type\":1,\"mchid\":\"").append(mchId)
                .append("\",\"out_trade_no\":\"").append(esc(c.outTradeNo())).append("\"},")
                .append("\"logistics_type\":").append(c.logisticsType()).append(',')
                // 拆单发货（2）只有快递才允许（10060006），我们暂不支持
                .append("\"delivery_mode\":1,")
                .append("\"shipping_list\":[{\"item_desc\":\"")
                .append(esc(trim(c.itemDesc(), 120))).append('"');
        if (c.logisticsType() == 1) {
            sb.append(",\"tracking_no\":\"").append(esc(c.trackingNo()))
                    .append("\",\"express_company\":\"").append(esc(c.expressCompany())).append('"');
        }
        sb.append("}],")
                .append("\"upload_time\":\"")
                .append(OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)).append("\",")
                .append("\"payer\":{\"openid\":\"").append(esc(c.payerOpenid())).append("\"}}");
        return sb.toString();
    }

    private static String trim(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 与其它通道各管一份 stable_token：拿到的是同一个 token，而可用性不互相绑死 */
    private String accessToken() throws Exception {
        long now = System.currentTimeMillis();
        String t = token;
        if (t != null && now < tokenExpireAt) {
            return t;
        }
        String body = "{\"grant_type\":\"client_credential\",\"appid\":\"" + appid
                + "\",\"secret\":\"" + secret + "\"}";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(host + "/cgi-bin/stable_token"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        String resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body();
        String got = strOf(resp, Pattern.compile("\"access_token\"\\s*:\\s*\"([^\"]*)\""));
        if (got == null || got.isBlank()) {
            throw new IllegalStateException("取 access_token 失败：" + strOf(resp, ERRMSG));
        }
        // 提前 5 分钟过期，免得卡在边界上用一个刚失效的 token
        tokenExpireAt = now + (intOf(resp, Pattern.compile("\"expires_in\"\\s*:\\s*(\\d+)"), 7200) - 300) * 1000L;
        token = got;
        return got;
    }

    private static int intOf(String s, Pattern p, int dft) {
        Matcher m = p.matcher(s);
        return m.find() ? Integer.parseInt(m.group(1)) : dft;
    }

    private static String strOf(String s, Pattern p) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : null;
    }
}
