package ai.neargo.shop.logistics.domain;

import ai.neargo.shop.logistics.config.LogisticsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 收件人手机号的加密（AES-GCM）。物流要在登记之后几小时到几天里用完整号码
 * （订阅：顺丰 / 中通必填；换 token：申通 / 中通必填），所以要存；存就存密文，终态即清。
 *
 * <p><b>密钥没配 → 不存密文</b>（同 {@code PhoneCrypto} 的失败方式）：明文永不落库这条不打折，
 * 代价是这几家的订阅 / 换 token 会失败、落 FATAL，运营看得见。启动时告警一次。
 */
@Component
public class PhoneCipher {

    private static final Logger log = LoggerFactory.getLogger(PhoneCipher.class);
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    /** 给 Spring 用的构造器。必须标 {@code @Autowired}，理由同 {@code CarrierCodeBook} */
    @Autowired
    public PhoneCipher(LogisticsProperties props) {
        this(props.getPhoneKey());
    }

    PhoneCipher(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            this.key = null;
            log.warn("[logistics] shop.logistics.phone-key 没配：收件人手机号不存密文，"
                    + "顺丰 / 中通的订阅与申通 / 中通的微信换 token 会失败");
        } else {
            this.key = new SecretKeySpec(sha256(rawKey), "AES");
        }
    }

    public boolean enabled() {
        return key != null;
    }

    /** @return Base64(iv ‖ 密文)；没配密钥或号码为空时返回 null */
    public String encrypt(String phone) {
        if (key == null || phone == null || phone.isBlank()) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LEN];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(phone.trim().getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
        } catch (Exception e) {
            throw new IllegalStateException("手机号加密失败", e);
        }
    }

    /** 解不开（密钥换过 / 没配）返回 null，不抛 —— 调用方按「没有手机号」处理 */
    public String decrypt(String enc) {
        if (key == null || enc == null || enc.isBlank()) {
            return null;
        }
        try {
            byte[] all = Base64.getDecoder().decode(enc);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_LEN));
            return new String(c.doFinal(all, IV_LEN, all.length - IV_LEN), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("[logistics] 收件人手机号解不开（密钥换过？）");
            return null;
        }
    }

    public static String last4(String phone) {
        if (phone == null) {
            return null;
        }
        String p = phone.trim();
        return p.length() >= 4 ? p.substring(p.length() - 4) : null;
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
