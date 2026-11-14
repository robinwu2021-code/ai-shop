package ai.neargo.secret.core;

import ai.neargo.secret.api.SecretException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM 的两层用法：数据密钥加密明文、主密钥包裹数据密钥（信封加密，表结构说明 §3.2）。
 *
 * <p>输出格式 {@code IV(12) || 密文 || 标签(16)}，存库时 Base64（{@code sec_secret_blob.cipher_text} 注释：含 IV 与认证标签）。
 * 两层都带同一份附加认证数据 —— 行的 {@code id|namespace|owner_type|owner_id|env}：
 * 把密文连同包裹的数据密钥一起搬到别的行，GCM 认证就过不去。
 */
final class EnvelopeCipher {

    static final String ALGORITHM = "AES-256-GCM";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private EnvelopeCipher() {
    }

    static byte[] newDataKey() {
        byte[] k = new byte[32];
        RANDOM.nextBytes(k);
        return k;
    }

    static byte[] encrypt(SecretKey key, byte[] plaintext, byte[] aad) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            c.updateAAD(aad);
            byte[] body = c.doFinal(plaintext);
            return ByteBuffer.allocate(iv.length + body.length).put(iv).put(body).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM encrypt failed", e);
        }
    }

    static byte[] decrypt(SecretKey key, byte[] sealed, byte[] aad) {
        if (sealed.length < IV_BYTES + TAG_BITS / 8) {
            throw new SecretException(SecretException.Reason.INTEGRITY, "ciphertext too short");
        }
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES));
            c.updateAAD(aad);
            return c.doFinal(sealed, IV_BYTES, sealed.length - IV_BYTES);
        } catch (AEADBadTagException e) {
            throw new SecretException(SecretException.Reason.INTEGRITY,
                    "authentication failed: ciphertext altered, moved to another row, or wrong master key");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM decrypt failed", e);
        }
    }

    static SecretKey dataKey(byte[] raw) {
        return new SecretKeySpec(raw, "AES");
    }

    /** 附加认证数据：{@code id|namespace|owner_type|owner_id|env} */
    static byte[] aad(long id, String namespace, String ownerType, long ownerId, String env) {
        return (id + "|" + namespace + "|" + ownerType + "|" + ownerId + "|" + env).getBytes(StandardCharsets.UTF_8);
    }

    static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 对称密钥的指纹：以材料为 HMAC 密钥、对固定标签做 HMAC-SHA256，取前 32 个十六进制字符。
     * 从指纹推不回材料；同一材料指纹稳定，运营可以拿它和对方核对「是不是同一把」。
     */
    static String symmetricFingerprint(byte[] material) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(material, "HmacSHA256"));
            byte[] out = mac.doFinal("ai.neargo.secret/fingerprint/v1".getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out).substring(0, 32);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
