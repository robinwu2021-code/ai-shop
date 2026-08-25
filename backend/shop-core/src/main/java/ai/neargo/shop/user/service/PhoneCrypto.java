package ai.neargo.shop.user.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 手机号的哈希与加密（人档专用）。
 *
 * <p><b>为什么不是 SHA-256(phone)</b>：手机号的取值空间只有十几亿，
 * 一张彩虹表几秒钟就能反查回来 —— 直接哈希**不是脱敏，是把明文换了种写法**。
 * 所以匹配用的是 <b>HMAC-SHA256 + 服务端 pepper</b>：拿到库也反查不出号码，
 * 除非同时拿到配置里的密钥。
 *
 * <p><b>两个密钥的失败方式刻意不同</b>：
 * <ul>
 *   <li>pepper 没配 → <b>用一个固定的开发默认值并每次启动告警</b>。哈希是身份的锚，
 *       它必须永远算得出来 —— 缺配置降低的是隐私强度，不该降低可用性
 *       （登录链路上抛异常 = 全站登不进来）</li>
 *   <li>加密密钥没配 → <b>不存密文</b>（{@code phone_enc} 留空），只留哈希与后四位。
 *       明文永不落库这条不打折；代价是日后申诉时解不出完整号，
 *       那时让本人重新验证一次即可</li>
 * </ul>
 */
@Component
public class PhoneCrypto {

    private static final Logger log = LoggerFactory.getLogger(PhoneCrypto.class);

    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final String HMAC = "HmacSHA256";
    /** 只在没配 pepper 时使用。**生产必须配**，否则库被读走等于手机号被读走 */
    private static final String DEV_PEPPER = "ai-shop-dev-pepper-do-not-use-in-prod";

    private final SecureRandom random = new SecureRandom();
    private final byte[] pepper;
    private final SecretKeySpec encKey;

    public PhoneCrypto(@Value("${shop.person.phone-pepper:}") String pepperConf,
                       @Value("${shop.person.phone-key:}") String keyBase64) {
        if (pepperConf == null || pepperConf.isBlank()) {
            log.warn("[person] 未配置 shop.person.phone-pepper，手机号哈希用的是开发默认值 —— "
                    + "生产环境必须配，否则库被读走等于手机号被读走");
            this.pepper = DEV_PEPPER.getBytes(StandardCharsets.UTF_8);
        } else {
            this.pepper = pepperConf.trim().getBytes(StandardCharsets.UTF_8);
        }
        this.encKey = (keyBase64 == null || keyBase64.isBlank())
                ? null : new SecretKeySpec(decodeKey(keyBase64), "AES");
        if (this.encKey == null) {
            log.warn("[person] 未配置 shop.person.phone-key，手机号不落密文 —— "
                    + "只存哈希与后四位；日后申诉要看完整号时需本人重新验证");
        }
    }

    private static byte[] decodeKey(String keyBase64) {
        byte[] k = Base64.getDecoder().decode(keyBase64.trim());
        if (k.length != 16 && k.length != 24 && k.length != 32) {
            throw new IllegalStateException(
                    "shop.person.phone-key 解码后必须是 16/24/32 字节，实际 " + k.length);
        }
        return k;
    }

    /** 归一化后做 HMAC。空号返回 null —— 调用方据此判断「这人没有手机号」 */
    public String hash(String phone) {
        String p = normalize(phone);
        if (p == null) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(pepper, HMAC));
            return HexFormat.of().formatHex(mac.doFinal(p.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("手机号哈希失败", e);
        }
    }

    /** 后四位。位数不够时原样返回 —— 测试号与境外号不该在这里抛 */
    public String tail(String phone) {
        String p = normalize(phone);
        if (p == null) {
            return null;
        }
        return p.length() <= 4 ? p : p.substring(p.length() - 4);
    }

    /** 明文 → base64(iv‖密文‖tag)。**没配密钥就返回 null，绝不明文落库** */
    public String encrypt(String phone) {
        String p = normalize(phone);
        if (p == null || encKey == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance(TRANSFORM);
            c.init(Cipher.ENCRYPT_MODE, encKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] out = c.doFinal(p.getBytes(StandardCharsets.UTF_8));
            byte[] all = new byte[iv.length + out.length];
            System.arraycopy(iv, 0, all, 0, iv.length);
            System.arraycopy(out, 0, all, iv.length, out.length);
            return Base64.getEncoder().encodeToString(all);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("手机号加密失败", e);
        }
    }

    /**
     * 解密。<b>只给平台申诉处置用</b>，调用方要自己落审计日志。
     *
     * @return 密文为空或密钥没配时返回 null
     */
    public String decrypt(String cipherText) {
        if (cipherText == null || cipherText.isBlank() || encKey == null) {
            return null;
        }
        try {
            byte[] all = Base64.getDecoder().decode(cipherText);
            byte[] iv = java.util.Arrays.copyOfRange(all, 0, IV_LEN);
            byte[] body = java.util.Arrays.copyOfRange(all, IV_LEN, all.length);
            Cipher c = Cipher.getInstance(TRANSFORM);
            c.init(Cipher.DECRYPT_MODE, encKey, new GCMParameterSpec(TAG_BITS, iv));
            return new String(c.doFinal(body), StandardCharsets.UTF_8);
        } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("手机号解密失败", e);
        }
    }

    /** 去空白与常见分隔符。同一个号写成 138-0013-8000 与 13800138000 必须算同一个人 */
    private static String normalize(String phone) {
        if (phone == null) {
            return null;
        }
        String p = phone.replaceAll("[\\s\\-()]", "");
        return p.isBlank() ? null : p;
    }
}
