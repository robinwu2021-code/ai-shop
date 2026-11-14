package ai.neargo.shop.pay.openapp;

import ai.neargo.shop.pay.openapp.entity.PayApp;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * ⏸ 试点过渡（原 {@code ConfigAppKeyResolver}；安全规范 §3.1）：接入方 HMAC 密钥在部署配置里 ——
 * {@code shop.pay.open.apps.{appNo}.hmac-secret}，轮换期另配 {@code .hmac-secret-next}。<b>没有默认值</b>。
 * {@code shop.pay.open.key-source=secret} 时由 {@code SecretAppSignatures} 取代（开发计划 M3.10 切换）。
 *
 * <p>配置里可以写带前缀的整串 AppSecret（运维照接入方拿到的那串贴），HMAC 只用前缀之后那段（API 清单 §7 P1）。
 * 敏感字段的 EncryptKey 在 {@code .encrypt-key}（轮换期另配 {@code .encrypt-key-next}；M3.5）。
 */
@Component
@ConditionalOnProperty(name = "shop.pay.open.key-source", havingValue = "config", matchIfMissing = true)
public class ConfigAppSignatures implements AppSignatureVerifier, AppSigner, AppFieldCipher {

    /** AppSecret 的展示前缀（PartnerKeyService 发密钥时加，不参与 HMAC） */
    static final List<String> DISPLAY_PREFIXES = List.of("sk_live_", "sk_test_");

    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    private final Environment env;

    public ConfigAppSignatures(Environment env) {
        this.env = env;
    }

    @Override
    public Optional<Verified> verify(PayApp app, String keySerial, String toSign, String signature) {
        byte[] given = decode(signature);
        if (given == null) {
            return Optional.empty();
        }
        byte[] data = toSign.getBytes(StandardCharsets.UTF_8);
        for (String secret : secretsOf(app.getAppNo())) {
            if (MessageDigest.isEqual(hmac(secret, data), given)) {
                return Optional.of(new Verified(null));
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<Signature> sign(PayApp app, Integer version, byte[] data) {
        // 只用当前那把：只配了 next（轮换配到一半）时不签，宁可推送失败重试，也不拿接入方还没换上的新密钥签
        String current = current(app.getAppNo());
        return current == null ? Optional.empty()
                : Optional.of(new Signature(Base64.getEncoder().encodeToString(hmac(current, data)), null, null));
    }

    @Override
    public Optional<byte[]> decrypt(PayApp app, String encryptSerial, byte[] packed, byte[] aad) {
        if (packed == null || packed.length < 12 + 16) {
            return Optional.empty();
        }
        for (String suffix : List.of("encrypt-key", "encrypt-key-next")) {
            String k = env.getProperty("shop.pay.open.apps." + app.getAppNo() + "." + suffix);
            if (k == null || k.isBlank()) {
                continue;
            }
            try {
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(k.trim().getBytes(StandardCharsets.UTF_8), "AES"),
                        new GCMParameterSpec(128, packed, 0, 12));
                c.updateAAD(aad);
                return Optional.of(c.doFinal(packed, 12, packed.length - 12));
            } catch (Exception wrongKeyOrTampered) {
                // 换下一把；都不对就是解不开，不分哪一步
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<Encrypted> encrypt(PayApp app, Integer version, byte[] plaintext, byte[] aad) {
        String k = env.getProperty("shop.pay.open.apps." + app.getAppNo() + ".encrypt-key");
        if (k == null || k.isBlank()) {
            return Optional.empty();
        }
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(k.trim().getBytes(StandardCharsets.UTF_8), "AES"),
                    new GCMParameterSpec(128, iv));
            c.updateAAD(aad);
            byte[] ct = c.doFinal(plaintext);
            byte[] packed = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(ct, 0, packed, iv.length, ct.length);
            return Optional.of(new Encrypted(packed, null, null));
        } catch (Exception e) {
            throw new IllegalStateException("AES-GCM unavailable or EncryptKey is not 32 bytes", e);
        }
    }

    private String current(String appNo) {
        String k = env.getProperty("shop.pay.open.apps." + appNo + ".hmac-secret");
        return k == null || k.isBlank() ? null : stripPrefix(k.trim());
    }

    /** 当前那把在前，轮换期的 next 在后 */
    private List<String> secretsOf(String appNo) {
        List<String> keys = new ArrayList<>();
        for (String suffix : List.of("hmac-secret", "hmac-secret-next")) {
            String k = env.getProperty("shop.pay.open.apps." + appNo + "." + suffix);
            if (k != null && !k.isBlank()) {
                keys.add(stripPrefix(k.trim()));
            }
        }
        return keys;
    }

    static String stripPrefix(String secret) {
        for (String p : DISPLAY_PREFIXES) {
            if (secret.startsWith(p)) {
                return secret.substring(p.length());
            }
        }
        return secret;
    }

    private static byte[] decode(String signature) {
        try {
            return signature == null ? null : Base64.getDecoder().decode(signature.trim());
        } catch (IllegalArgumentException notBase64) {
            return null;
        }
    }

    private static byte[] hmac(String secret, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
