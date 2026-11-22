package ai.neargo.shop.invoice.channel.base;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-CBC / PKCS7 报文加密（Java 的 PKCS5Padding 对 AES 即 PKCS7），输出 Base64。
 * {@code urlEncode} 为 true 时先 URL Encode 明文、解密后再 URL Decode（绿界 B2C 发票的做法）。
 * Key 长度决定 AES-128 / 192 / 256。
 */
public final class AesCbcCipher {

    private final SecretKeySpec key;
    private final IvParameterSpec iv;
    private final boolean urlEncode;

    public AesCbcCipher(String key, String iv, boolean urlEncode) {
        this.key = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES");
        this.iv = new IvParameterSpec(iv.getBytes(StandardCharsets.UTF_8));
        this.urlEncode = urlEncode;
    }

    public String encrypt(String plain) {
        String text = urlEncode ? URLEncoder.encode(plain, StandardCharsets.UTF_8) : plain;
        return Base64.getEncoder().encodeToString(run(Cipher.ENCRYPT_MODE, text.getBytes(StandardCharsets.UTF_8)));
    }

    public String decrypt(String base64) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new AbstractInvoiceGateway.AccountMisconfiguredException("aes-cbc bad base64", e);
        }
        String text = new String(run(Cipher.DECRYPT_MODE, raw), StandardCharsets.UTF_8);
        return urlEncode ? URLDecoder.decode(text, StandardCharsets.UTF_8) : text;
    }

    private byte[] run(int mode, byte[] input) {
        try {
            var cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(mode, key, iv);
            return cipher.doFinal(input);
        } catch (GeneralSecurityException e) {
            // 密钥配错（长度不对）或用错密钥解不开：都是账号问题，不是网络问题
            throw new AbstractInvoiceGateway.AccountMisconfiguredException(
                    "aes-cbc " + e.getClass().getSimpleName(), e);
        }
    }
}
