package ai.neargo.shop.pay.channel.wechat;

import ai.neargo.shop.pay.channel.WechatApiV3Signer;
import ai.neargo.shop.pay.channel.api.ChannelTransportException;
import ai.neargo.shop.pay.channel.http.ChannelHttpRequest;
import ai.neargo.shop.pay.channel.http.ChannelHttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 微信支付 APIv3 协议（Adapter，渠道接入统一封装设计 §6.3；TDD-支付服务-W2渠道层补完 S5）：<b>只做协议</b> ——
 * 请求签名（{@code Authorization} 头）、应答验签、错误应答归类、小程序 paySign。不懂下单退款是什么，也不发 HTTP。
 *
 * <p>签名与验签自 {@link WechatApiV3Signer}（原样留用）；头、错误分类、「应答不是 JSON」的处理自原
 * {@code WechatChannelClient} 逐字搬来，三件容易被省掉而省掉不报错的事都还在：query 参与签名（由调用方把 query 放进路径）、
 * 验应答签名（验不过 = 没收到）、5xx / 429 与 4xx 分开（前者渠道可能已处理，后者是这个请求本身不行）。
 */
public final class WechatV3Protocol {

    /** 主域名。故障时切备域名 —— 微信明确要求商户实现主备切换（{@link WechatV3Client}） */
    public static final String HOST = "https://api.mch.weixin.qq.com";
    public static final String HOST_BACKUP = "https://api2.mch.weixin.qq.com";

    /** 微信要求带 User-Agent，缺了会被拒 */
    private static final String USER_AGENT = "ai-shop/1.0 (wechatpay-apiv3)";
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final WechatApiV3Signer signer;

    public WechatV3Protocol(WechatApiV3Signer signer) {
        this.signer = signer;
    }

    /**
     * 签好名的请求。<b>每次调用都重新取随机串与时间戳</b>（主备切换的第二次也是）—— 同一组重放会被微信拒。
     *
     * @param pathAndQuery 含 query 的路径：query 参与签名
     * @param body         POST 的业务参数；GET 传 null（待签 body 是空串，不是没有那一行）
     */
    public ChannelHttpRequest request(String host, String method, String pathAndQuery, Map<String, Object> body) {
        boolean get = "GET".equals(method);
        String payload = get ? "" : JSON.writeValueAsString(body == null ? Map.of() : body);
        String nonce = WechatApiV3Signer.nonce();
        long ts = System.currentTimeMillis() / 1000;
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        headers.put("User-Agent", USER_AGENT);
        headers.put("Authorization", signer.authorization(method, pathAndQuery, payload, nonce, ts));
        if (!get) {
            headers.put("Content-Type", "application/json");
        }
        return new ChannelHttpRequest(method, host, pathAndQuery, headers, payload);
    }

    /**
     * 读应答：非 2xx 抛（5xx / 429 → {@code SERVER_ERROR}，其余 → {@code CLIENT_ERROR} 带渠道码）；验签不过 →
     * {@code BAD_SIGNATURE}；不是 JSON → {@code UNPARSEABLE}；空体 → 空 map（204 之类，交给调用方按缺字段判）。
     * 异常信息里<b>不带签名串与报文主体</b>。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> read(String api, ChannelHttpResponse resp) {
        int status = resp.status();
        String text = resp.body();
        if (!resp.ok()) {
            Map<String, Object> err = parseQuietly(text);
            String code = err.get("code") == null ? null : String.valueOf(err.get("code"));
            Object message = err.get("message");
            String detail = (code == null ? (text.isBlank() ? "无应答体" : "未知错误码") : code)
                    + (message == null ? "" : "（" + message + "）");
            boolean mayHaveProcessed = status >= 500 || status == 429;
            throw new ChannelTransportException(mayHaveProcessed ? ChannelTransportException.Kind.SERVER_ERROR
                    : ChannelTransportException.Kind.CLIENT_ERROR, status, code,
                    "微信 " + api + " HTTP " + status + "：" + detail, null);
        }
        if (!signer.verifyResponse(resp.header("Wechatpay-Timestamp"), resp.header("Wechatpay-Nonce"), text,
                resp.header("Wechatpay-Signature"))) {
            throw new ChannelTransportException(ChannelTransportException.Kind.BAD_SIGNATURE, status, null,
                    "微信 " + api + " 应答验签失败", null);
        }
        if (text.isBlank()) {
            return Map.of();
        }
        try {
            return JSON.readValue(text, Map.class);
        } catch (JacksonException | ClassCastException e) {
            throw new ChannelTransportException(ChannelTransportException.Kind.UNPARSEABLE, status, null,
                    "微信 " + api + " 应答不是 JSON（HTTP " + status + "）", null);
        }
    }

    /**
     * 小程序 {@code wx.requestPayment} 的六件套。{@code package} 是 {@code prepay_id=} <b>带前缀的整串</b>，
     * paySign 签的也是这个整串 —— 传裸 prepay_id 的话端上报「支付验证签名失败」，而后端一切正常。
     */
    public Map<String, String> jsapiParams(String appId, String prepayId) {
        String timeStamp = String.valueOf(System.currentTimeMillis() / 1000);
        String nonceStr = WechatApiV3Signer.nonce();
        String pkg = "prepay_id=" + prepayId;
        Map<String, String> six = new LinkedHashMap<>();
        six.put("appId", appId);
        six.put("timeStamp", timeStamp);
        six.put("nonceStr", nonceStr);
        six.put("package", pkg);
        six.put("signType", WechatApiV3Signer.SIGN_TYPE);
        six.put("paySign", signer.jsapiPaySign(appId, timeStamp, nonceStr, pkg));
        return six;
    }

    /**
     * paySign 自验：用从私钥推出的公钥验回自己刚签的名。端上报「支付验证签名失败」时先回答「是我们签错了还是对方不认」。
     * 结果进处理器的返回（{@code Pending.channelStatus}），由管线日志带出 —— 处理器不打日志。
     */
    public boolean selfCheck(Map<String, String> six) {
        return signer.verifyOwnSignature(WechatApiV3Signer.paySignContent(six.get("appId"), six.get("timeStamp"),
                six.get("nonceStr"), six.get("package")), six.get("paySign"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseQuietly(String text) {
        if (text == null || text.isBlank()) {
            return Map.of();
        }
        try {
            Object v = JSON.readValue(text, Object.class);
            return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        } catch (JacksonException e) {
            return Map.of();
        }
    }
}
