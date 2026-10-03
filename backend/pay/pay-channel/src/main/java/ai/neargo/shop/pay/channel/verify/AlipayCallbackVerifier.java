package ai.neargo.shop.pay.channel.verify;

import ai.neargo.shop.spi.pay.ChannelCallbackVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 支付宝异步通知验签（RSA2）。
 *
 * <p>与微信是<b>两种形状</b>：支付宝的通知是 {@code application/x-www-form-urlencoded}，
 * 业务字段就在参数里（没有加密层）；待签串是**除 {@code sign} 与 {@code sign_type} 之外
 * 的全部参数按 key 升序拼 {@code k=v&...}**，值用**解码后的原文**。
 *
 * <p><b>两个坑写在这儿</b>：
 * <ul>
 *   <li><b>要用解码后的值</b>。拿 URL 编码的原串去拼，签名永远对不上，
 *       而错误现象是「支付宝签名有问题」—— 会让人去查一个没问题的地方。</li>
 *   <li><b>空值参数要排除</b>。支付宝规定空串不参与签名；带上它签名不过。</li>
 * </ul>
 *
 * <p><b>默认不装配</b>（{@code shop.pay.alipay.enabled}）。理由同微信那个。
 */
@Component
@ConditionalOnProperty(name = "shop.pay.alipay.enabled", havingValue = "true")
public class AlipayCallbackVerifier implements ChannelCallbackVerifier {

    private static final Logger log = LoggerFactory.getLogger(AlipayCallbackVerifier.class);

    private final String publicKey;

    public AlipayCallbackVerifier(@Value("${shop.pay.alipay.public-key:}") String publicKey) {
        this.publicKey = publicKey;
    }

    @Override
    public String payChannel() {
        return "ALIPAY";
    }

    /** form-urlencoded → 参数表。值做 URL 解码 —— 待签串要的是原文。 */
    static Map<String, String> parseForm(String rawBody) {
        Map<String, String> out = new LinkedHashMap<>();
        if (rawBody == null || rawBody.isBlank()) {
            return out;
        }
        for (String pair : rawBody.split("&")) {
            int i = pair.indexOf('=');
            if (i <= 0) {
                continue;
            }
            out.put(URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    /** 待签串：去掉 sign / sign_type 与空值，按 key 升序拼 {@code k=v&...}。 */
    static String signContent(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : new TreeMap<>(params).entrySet()) {
            String k = e.getKey();
            String v = e.getValue();
            if ("sign".equals(k) || "sign_type".equals(k) || v == null || v.isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append('&');
            }
            sb.append(k).append('=').append(v);
        }
        return sb.toString();
    }

    @Override
    public Map<String, Object> verify(Map<String, String> headers, String rawBody) {
        try {
            Map<String, String> params = parseForm(rawBody);
            String sign = params.get("sign");
            if (sign == null) {
                return null;
            }
            byte[] der = Base64.getDecoder().decode(publicKey.replaceAll("\\s|-----[A-Z ]+-----", ""));
            PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(key);
            verifier.update(signContent(params).getBytes(StandardCharsets.UTF_8));
            if (!verifier.verify(Base64.getDecoder().decode(sign))) {
                return null;
            }
            return new LinkedHashMap<>(params);
        } catch (Exception e) {
            log.warn("[callback] 支付宝回调验签失败");
            return null;
        }
    }

    /** 支付宝要纯文本 {@code success}，回别的它会一直重推。 */
    @Override
    public String ackOk() {
        return "success";
    }

    @Override
    public String ackFail() {
        return "fail";
    }
}
