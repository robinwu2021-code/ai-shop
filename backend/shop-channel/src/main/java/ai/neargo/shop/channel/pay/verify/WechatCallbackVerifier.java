package ai.neargo.shop.channel.pay.verify;

import ai.neargo.shop.spi.pay.ChannelCallbackVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;

/**
 * 微信支付 APIv3 回调验签 + 解密。
 *
 * <p>两步，缺一不可：
 * <ol>
 *   <li><b>验签</b>：待签串是 {@code timestamp\nnonce\nbody\n}（<b>结尾那个换行也要</b>），
 *       用微信平台证书的公钥做 SHA256withRSA。签名与时间戳、随机串都在请求头里。</li>
 *   <li><b>解密</b>：验过之后 {@code resource} 才是密文，用 APIv3 密钥
 *       AES-256-GCM 解出业务报文。{@code associated_data} 参与认证 ——
 *       它对不上时解密必须失败，那正是 GCM 的作用。</li>
 * </ol>
 *
 * <p><b>顺序不能反。</b>先解密后验签的话，攻击者可以拿一段自己加密的密文进来 ——
 * 而解密成功会让人以为「能解出来就是真的」。
 *
 * <p><b>默认不装配</b>（{@code shop.pay.wechat.enabled}）。没有平台证书时装配它，
 * 结果是每一条回调都验签失败 —— 而通道会一直重推，日志刷满而没人知道是配置没给。
 */
@Component
@ConditionalOnProperty(name = "shop.pay.wechat.enabled", havingValue = "true")
public class WechatCallbackVerifier implements ChannelCallbackVerifier {

    private static final Logger log = LoggerFactory.getLogger(WechatCallbackVerifier.class);

    private final String apiV3Key;
    private final String platformPublicKey;
    private final ObjectMapper json;

    public WechatCallbackVerifier(@Value("${shop.pay.wechat.apiv3-key:}") String apiV3Key,
                                  @Value("${shop.pay.wechat.platform-public-key:}") String platformPublicKey,
                                  ObjectMapper json) {
        this.apiV3Key = apiV3Key;
        this.platformPublicKey = platformPublicKey;
        this.json = json;
    }

    @Override
    public String payChannel() {
        return "WECHAT";
    }

    /**
     * 待签串。<b>三个字段各占一行，最后一行也要换行</b> ——
     * 少一个 {@code \n} 签名恒不过，而那种失败看起来像「平台证书配错了」，
     * 会让人去查一个没问题的地方。
     */
    static String signContent(String timestamp, String nonce, String body) {
        return timestamp + "\n" + nonce + "\n" + body + "\n";
    }

    @Override
    public Map<String, Object> verify(Map<String, String> headers, String rawBody) {
        try {
            String sign = header(headers, "wechatpay-signature");
            String timestamp = header(headers, "wechatpay-timestamp");
            String nonce = header(headers, "wechatpay-nonce");
            if (sign == null || timestamp == null || nonce == null || rawBody == null) {
                return null;
            }
            if (!rsaVerify(signContent(timestamp, nonce, rawBody), sign)) {
                return null;
            }
            Map<String, Object> envelope = json.readValue(rawBody, Map.class);
            Object resource = envelope.get("resource");
            if (!(resource instanceof Map<?, ?> res)) {
                return null;
            }
            String plain = decrypt(String.valueOf(res.get("associated_data")),
                    String.valueOf(res.get("nonce")),
                    String.valueOf(res.get("ciphertext")));
            return json.readValue(plain, Map.class);
        } catch (Exception e) {
            // 验签/解密的任何异常都当「这条回调不存在」。**不回原因** —— 端点公网可达
            log.warn("[callback] 微信回调验签失败");
            return null;
        }
    }

    /** 请求头大小写不敏感，而 Spring 给的 Map 是原样的 —— 逐个比小写才稳。 */
    private static String header(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().toLowerCase().equals(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    private boolean rsaVerify(String content, String signBase64) throws Exception {
        byte[] der = Base64.getDecoder().decode(platformPublicKey.replaceAll("\\s|-----[A-Z ]+-----", ""));
        PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(key);
        verifier.update(content.getBytes(StandardCharsets.UTF_8));
        return verifier.verify(Base64.getDecoder().decode(signBase64));
    }

    /** AES-256-GCM。tag 长度 128 位，附加数据参与认证 —— 改一个字节就解不出来。 */
    private String decrypt(String associatedData, String nonce, String ciphertext) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,
                new SecretKeySpec(apiV3Key.getBytes(StandardCharsets.UTF_8), "AES"),
                new GCMParameterSpec(128, nonce.getBytes(StandardCharsets.UTF_8)));
        cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
        return new String(cipher.doFinal(Base64.getDecoder().decode(ciphertext)), StandardCharsets.UTF_8);
    }

    /** 微信要 JSON。回错了不会报错，只会让通道一直重推。 */
    @Override
    public String ackOk() {
        return "{\"code\":\"SUCCESS\",\"message\":\"OK\"}";
    }

    @Override
    public String ackFail() {
        return "{\"code\":\"FAIL\",\"message\":\"FAIL\"}";
    }
}
