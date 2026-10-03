package ai.neargo.shop.message.notify;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 商家自带渠道凭据的**落库加密**（设计：触达推送中台-模块抽象 · N5 · 外部接入）。
 *
 * <p>外部接入下商家把自己的短信签名 / 推送账号密钥交给平台。这些密钥**不能明文落库**：
 * 库一旦被读走就是一批商家的群发权限。用 AES-256-GCM 加密存 {@code notify_channel.secret_cipher}，
 * 读时解密进内存供发送用，**永不回前端**（前端只看 present/状态）。
 *
 * <p><b>失败即拒，绝不退回明文</b>：密钥（{@code SHOP_NOTIFY_CRED_KEY}）没配就抛，
 * 而不是「先明文存着回头再加密」——那个「回头」永远不会来，而明文已经在库里了。
 *
 * <p>GCM 自带完整性校验：密文被改一个字节，解密直接抛而不是给出错误明文（同
 * {@code PushCrypto} 的思路，纯 JDK 无第三方库）。IV 每条随机、拼在密文前。
 */
@Component
public class NotifyCredCipher {

    private static final int IV_LEN = 12;      // GCM 推荐 96 bit
    private static final int TAG_BITS = 128;
    private static final String TRANSFORM = "AES/GCM/NoPadding";

    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;

    public NotifyCredCipher(@Value("${shop.notify.cred-key:}") String keyBase64) {
        // 空 = 未配置：不在构造期抛（否则没用到外部接入的部署也起不来），
        // 而在 encrypt/decrypt 时抛 —— 用到才要求配。
        this.key = (keyBase64 == null || keyBase64.isBlank())
                ? null : new SecretKeySpec(decodeKey(keyBase64), "AES");
    }

    private static byte[] decodeKey(String keyBase64) {
        byte[] k = Base64.getDecoder().decode(keyBase64.trim());
        if (k.length != 16 && k.length != 24 && k.length != 32) {
            throw new IllegalStateException(
                    "SHOP_NOTIFY_CRED_KEY 解码后必须是 16/24/32 字节（AES-128/192/256），实际 " + k.length);
        }
        return k;
    }

    /** 是否已配置密钥。外部接入的保存入口应先查它，缺钥时给运营/商家一个可读提示。 */
    public boolean configured() {
        return key != null;
    }

    /** 明文 → base64(iv‖密文‖tag)。密钥未配置时抛（绝不明文落库）。 */
    public String encrypt(String plaintext) {
        requireKey();
        try {
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance(TRANSFORM);
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("商家渠道凭据加密失败：" + e.getMessage(), e);
        }
    }

    /** base64(iv‖密文‖tag) → 明文。密文被篡改会抛（GCM 校验），不返回错误明文。 */
    public String decrypt(String cipherBase64) {
        requireKey();
        try {
            byte[] all = Base64.getDecoder().decode(cipherBase64);
            byte[] iv = new byte[IV_LEN];
            System.arraycopy(all, 0, iv, 0, IV_LEN);
            Cipher c = Cipher.getInstance(TRANSFORM);
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] pt = c.doFinal(all, IV_LEN, all.length - IV_LEN);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("商家渠道凭据解密失败（密钥不对或密文被改）：" + e.getMessage(), e);
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new IllegalStateException(
                    "外部接入需要 SHOP_NOTIFY_CRED_KEY 加密商家凭据，但它没配 —— "
                            + "不配就不能存商家密钥（绝不明文落库）");
        }
    }
}
