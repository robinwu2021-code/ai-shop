package ai.neargo.shop.merchant.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 供应商收款账号的**落库加密**（ADR-011 · V358）。
 *
 * <p>银行账号不能明文落库：库一旦被读走就是一批供应商的收款账号，
 * 而账号加户名足以被用来伪造付款指令。AES-256-GCM 存
 * {@code mch_payout_account.account_number_enc}，<b>解密只在「导出付款清单」
 * 一个入口发生</b>，其余任何地方（列表、详情、日志）一律只用
 * {@code account_masked}。
 *
 * <p><b>为什么不复用 {@code NotifyCredCipher}</b>：那个类的实现完全够用，
 * 这里照搬了它的做法（GCM、随机 IV 前置、失败即抛）。不共用的是<b>密钥</b> ——
 * 通知渠道凭据与资金账户共一把钥匙的话，一处泄露就是两处全泄。
 * 密钥分开之后，两者的轮换周期也能各自定。
 *
 * <p><b>失败即拒，绝不退回明文</b>：密钥没配就抛，而不是「先明文存着回头再加密」——
 * 那个「回头」永远不会来，而明文已经在库里了。
 */
@Component
public class PayoutAccountCipher {

    private static final int IV_LEN = 12;      // GCM 推荐 96 bit
    private static final int TAG_BITS = 128;
    private static final String TRANSFORM = "AES/GCM/NoPadding";

    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;

    public PayoutAccountCipher(@Value("${shop.pay.payout-account-key:}") String keyBase64) {
        // 空 = 未配置：不在构造期抛（否则没跑自营付款的部署也起不来），
        // 而在 encrypt/decrypt 时抛 —— 用到才要求配。
        this.key = (keyBase64 == null || keyBase64.isBlank())
                ? null : new SecretKeySpec(decodeKey(keyBase64), "AES");
    }

    private static byte[] decodeKey(String keyBase64) {
        byte[] k = Base64.getDecoder().decode(keyBase64.trim());
        if (k.length != 16 && k.length != 24 && k.length != 32) {
            throw new IllegalStateException(
                    "SHOP_PAY_PAYOUT_ACCOUNT_KEY 解码后必须是 16/24/32 字节（AES-128/192/256），实际 "
                            + k.length);
        }
        return k;
    }

    /** 是否已配置密钥。保存入口应先查它，缺钥时给一个可读提示而不是一个栈。 */
    public boolean configured() {
        return key != null;
    }

    /** 明文账号 → base64(iv‖密文‖tag)。密钥未配置时抛（绝不明文落库）。 */
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
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("收款账号加密失败：" + e.getMessage(), e);
        }
    }

    /** base64(iv‖密文‖tag) → 明文账号。密文被篡改会抛（GCM 校验），不返回错误明文。 */
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
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(
                    "收款账号解密失败（密钥不对或密文被改）：" + e.getMessage(), e);
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new IllegalStateException(
                    "自营付款需要 SHOP_PAY_PAYOUT_ACCOUNT_KEY 加密供应商收款账号，但它没配 —— "
                            + "不配就不能存收款账户（绝不明文落库）");
        }
    }
}
