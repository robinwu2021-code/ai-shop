package ai.neargo.shop.pay.channel.alipay;

import ai.neargo.shop.pay.channel.api.ChannelTransportException;
import ai.neargo.shop.pay.channel.http.ChannelHttpRequest;
import ai.neargo.shop.pay.channel.http.ChannelHttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 支付宝开放平台协议（RSA2）（Adapter，渠道接入统一封装设计 §6.3；TDD-支付服务-W2渠道层补完 S5）：只做签名与验签。
 *
 * <p>请求：公共参数 + {@code biz_content}（JSON），<b>除 {@code sign} 与空值之外</b>按键升序拼 {@code k=v&...}，
 * 应用私钥 SHA256withRSA 签，表单 POST 到网关。
 *
 * <p>应答：{@code {"alipay_trade_query_response":{...},"sign":"..."}}。<b>验的是 {@code xxx_response} 那一段原文</b>
 * （按字节截出来），不是解析后再序列化的 —— 字段顺序、空格、转义任何一处不同签名都对不上，而那种失败看起来像「支付宝公钥配错」。
 * 验不过 = 没收到（{@code BAD_SIGNATURE}，按不明处理）。
 */
public final class AlipayProtocol {

    public static final String GATEWAY = "https://openapi.alipay.com/gateway.do";
    private static final String HOST = "https://openapi.alipay.com";
    private static final String PATH = "/gateway.do";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** 支付宝的 timestamp 按北京时间解释 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final String appId;
    private final PrivateKey appPrivateKey;
    private final PublicKey alipayPublicKey;
    private final Clock clock;

    /**
     * @param appPrivateKeyPem    应用私钥（PKCS#8，PEM 头尾可有可无）
     * @param alipayPublicKeyPem  支付宝公钥（X.509）—— 验应答与验回调用同一把
     */
    public AlipayProtocol(String appId, String appPrivateKeyPem, String alipayPublicKeyPem, Clock clock) {
        this.appId = appId;
        this.appPrivateKey = privateKey(appPrivateKeyPem);
        this.alipayPublicKey = publicKey(alipayPublicKeyPem);
        this.clock = clock;
    }

    public ChannelHttpRequest request(String method, Map<String, Object> bizContent) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("app_id", appId);
        params.put("method", method);
        params.put("format", "JSON");
        params.put("charset", "utf-8");
        params.put("sign_type", "RSA2");
        params.put("timestamp", TS.format(clock.instant().atZone(ZONE)));
        params.put("version", "1.0");
        params.put("biz_content", JSON.writeValueAsString(bizContent));
        params.put("sign", sign(signContent(params)));
        StringJoiner form = new StringJoiner("&");
        params.forEach((k, v) -> form.add(URLEncoder.encode(k, StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(v, StandardCharsets.UTF_8)));
        return new ChannelHttpRequest("POST", HOST, PATH,
                Map.of("Content-Type", "application/x-www-form-urlencoded;charset=utf-8"), form.toString());
    }

    /**
     * 读应答：非 2xx 抛；顶层是 {@code error_response}（网关级错误，如 app_id 不对）→ {@code CLIENT_ERROR}；
     * {@code xxx_response} 原文验签不过 → {@code BAD_SIGNATURE}；返回那一段解析后的字段（业务码 {@code code} 由处理器判）。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> read(String method, ChannelHttpResponse resp) {
        if (!resp.ok()) {
            throw new ChannelTransportException(resp.status() >= 500 ? ChannelTransportException.Kind.SERVER_ERROR
                    : ChannelTransportException.Kind.CLIENT_ERROR, resp.status(), null,
                    "支付宝 " + method + " HTTP " + resp.status(), null);
        }
        String text = resp.body();
        String key = method.replace('.', '_') + "_response";
        String node = rawObject(text, "\"" + key + "\"");
        if (node == null) {
            String error = rawObject(text, "\"error_response\"");
            if (error != null) {
                Map<String, Object> e = parse(method, error);
                throw new ChannelTransportException(ChannelTransportException.Kind.CLIENT_ERROR, resp.status(),
                        String.valueOf(e.get("sub_code") != null ? e.get("sub_code") : e.get("code")),
                        "支付宝 " + method + " 网关错误 " + e.get("code") + " " + e.get("sub_code"), null);
            }
            throw new ChannelTransportException(ChannelTransportException.Kind.UNPARSEABLE, resp.status(), null,
                    "支付宝 " + method + " 应答里没有 " + key, null);
        }
        Map<String, Object> envelope = parse(method, text);
        Object sign = envelope.get("sign");
        if (!(sign instanceof String s) || !verify(node, s)) {
            throw new ChannelTransportException(ChannelTransportException.Kind.BAD_SIGNATURE, resp.status(), null,
                    "支付宝 " + method + " 应答验签失败", null);
        }
        return parse(method, node);
    }

    /** 待签串：去掉 sign / sign_type 之外的签名字段与空值，按键升序拼 {@code k=v&...}（请求与回调同一规则） */
    public static String signContent(Map<String, String> params) {
        StringJoiner sb = new StringJoiner("&");
        for (Map.Entry<String, String> e : new TreeMap<>(params).entrySet()) {
            String k = e.getKey();
            String v = e.getValue();
            if ("sign".equals(k) || v == null || v.isEmpty()) {
                continue;
            }
            sb.add(k + "=" + v);
        }
        return sb.toString();
    }

    private String sign(String content) {
        try {
            Signature s = Signature.getInstance("SHA256withRSA");
            s.initSign(appPrivateKey);
            s.update(content.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(s.sign());
        } catch (Exception e) {
            throw new IllegalStateException("支付宝请求签名失败", e);
        }
    }

    private boolean verify(String content, String signBase64) {
        try {
            Signature v = Signature.getInstance("SHA256withRSA");
            v.initVerify(alipayPublicKey);
            v.update(content.getBytes(StandardCharsets.UTF_8));
            return v.verify(Base64.getDecoder().decode(signBase64));
        } catch (Exception e) {
            return false;
        }
    }

    /** 从原文里按字节截出 {@code "key":{...}} 的那个对象（括号配对，跳过字符串里的括号与转义） */
    static String rawObject(String text, String quotedKey) {
        int k = text.indexOf(quotedKey);
        if (k < 0) {
            return null;
        }
        int start = text.indexOf('{', k + quotedKey.length());
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return text.substring(start, i + 1);
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String method, String json) {
        try {
            return JSON.readValue(json, Map.class);
        } catch (JacksonException | ClassCastException e) {
            throw new ChannelTransportException(ChannelTransportException.Kind.UNPARSEABLE, 200, null,
                    "支付宝 " + method + " 应答不是 JSON", null);
        }
    }

    private static PrivateKey privateKey(String pem) {
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der(pem)));
        } catch (Exception e) {
            throw new IllegalStateException("支付宝应用私钥解析失败（shop.pay.alipay.private-key）", e);
        }
    }

    private static PublicKey publicKey(String pem) {
        try {
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der(pem)));
        } catch (Exception e) {
            throw new IllegalStateException("支付宝公钥解析失败（shop.pay.alipay.public-key）", e);
        }
    }

    private static byte[] der(String pem) {
        return Base64.getDecoder().decode(pem.replaceAll("\\s|-----[A-Z ]+-----", ""));
    }
}
