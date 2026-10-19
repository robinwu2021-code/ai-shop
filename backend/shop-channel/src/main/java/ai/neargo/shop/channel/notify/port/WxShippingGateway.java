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
import java.util.List;
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

    /** Legacy scalar replay compatibility only; a mapped source cannot prove identity from this reply. */
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

            if (code == 0 || code == 10060023 || (code == ALREADY_SHIPPED && !cmd.mappedSource())) {
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
            // Unknown outcomes retry the captured body; mapped already-shipped replies require manual proof.
            log.warn("[wxship] 上报异常 outTradeNo={}：{}", cmd.outTradeNo(), e.toString());
            return Result.retry(-1, e.getClass().getSimpleName());
        }
    }

    /** 缺哪一件说哪一件。微信的参数码不会告诉你缺的是哪一个字段 */
    /**
     * 确认收货提醒。与 {@link #upload} 共用 access_token 与错误分类，但有两点不同：
     * <ul>
     *   <li><b>重复调用不当成功</b>：微信规定每单一次，第二次回的是真错。上层靠
     *       {@code confirm_notified_at} 保证只调一次，这里不替它兜底 —— 兜了就看不见重复调用这件事。</li>
     *   <li>10060029「签收时间非法」是 <b>fatal 不是可重试</b>：时间不会自己变合法，
     *       重试只是把同一个错再撞 N 次，而每撞一次都可能耗掉「每单一次」的那一次。</li>
     * </ul>
     */
    @Override
    public Result notifyConfirmReceive(ConfirmCmd cmd) {
        if (cmd == null || cmd.outTradeNo() == null || cmd.outTradeNo().isBlank()) {
            return Result.fatal(-1, "缺商户单号");
        }
        if (cmd.receivedAt() <= 0) {
            return Result.fatal(-1, "缺签收时间");
        }
        String body = "{\"merchant_id\":\"" + esc(mchId) + "\",\"merchant_trade_no\":\""
                + esc(cmd.outTradeNo()) + "\",\"received_time\":" + cmd.receivedAt() + "}";
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(host + "/wxa/sec/order/notify_confirm_receive?access_token=" + accessToken()))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            String resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body();
            int code = intOf(resp, ERRCODE, -1);
            String msg = strOf(resp, ERRMSG);
            if (code == 0) {
                log.info("[wxship] 确认收货提醒已发 outTradeNo={}", cmd.outTradeNo());
                return Result.ok();
            }
            boolean retryable = code == 40001 || code == 42001 || code == 45009;
            if (retryable) {
                token = null;
            }
            log.warn("[wxship] 确认收货提醒失败 outTradeNo={} errcode={} errmsg={} 可重试={}",
                    cmd.outTradeNo(), code, msg, retryable);
            return retryable ? Result.retry(code, msg) : Result.fatal(code, msg);
        } catch (Exception e) {
            log.warn("[wxship] 确认收货提醒异常 outTradeNo={}：{}", cmd.outTradeNo(), e.toString());
            return Result.retry(-1, e.getClass().getSimpleName());
        }
    }

    private static String validate(Command c) {
        if (blank(c.outTradeNo())) { return "缺商户单号"; }
        if (blank(c.payerOpenid())) { return "缺支付单付款人 openid"; }
        List<ShippingItem> shipments = itemsOf(c);
        if (shipments.isEmpty() || shipments.size() > 15) { return "发货包裹数量必须为1至15，不能截断上报"; }
        if (c.deliveryMode() != 1 && c.deliveryMode() != 2) { return "无效发货模式"; }
        if (c.deliveryMode() == 1 && shipments.size() != 1) { return "统一发货必须只有一个包裹"; }
        if (c.deliveryMode() == 2 && c.logisticsType() != 1) { return "拆单发货仅支持物流快递"; }
        for (ShippingItem item : shipments) {
            if (blank(item.itemDesc())) { return "缺商品描述（微信 10060008）"; }
            if (c.logisticsType() == 1 && (blank(item.trackingNo()) || blank(item.expressCompany()))) {
                return "快递发货缺运单号或快递公司";
            }
        }
        return null;
    }

    private static List<ShippingItem> itemsOf(Command c) {
        return c.shipments().isEmpty() && !c.mappedSource()
                ? List.of(new ShippingItem(null, null, null, c.trackingNo(), c.expressCompany(), c.itemDesc()))
                : c.shipments();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private String buildBody(Command c) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"order_key\":{\"order_number_type\":1,\"mchid\":\"").append(esc(mchId))
                .append("\",\"out_trade_no\":\"").append(esc(c.outTradeNo())).append("\"},")
                .append("\"logistics_type\":").append(c.logisticsType()).append(',')
                .append("\"delivery_mode\":").append(c.deliveryMode()).append(',');
        if (c.deliveryMode() == 2) { sb.append("\"is_all_delivered\":").append(c.allDelivered()).append(','); }
        sb.append("\"shipping_list\":[");
        boolean first = true;
        for (ShippingItem item : itemsOf(c)) {
            if (!first) { sb.append(','); }
            first = false;
            sb.append("{\"item_desc\":\"").append(esc(trim(item.itemDesc(), 120))).append('"');
            if (c.logisticsType() == 1) {
                sb.append(",\"tracking_no\":\"").append(esc(item.trackingNo()))
                        .append("\",\"express_company\":\"").append(esc(item.expressCompany())).append('"');
            }
            sb.append('}');
        }
        sb.append("],\"upload_time\":\"")
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
