package ai.neargo.shop.invoice.regime.tw;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 证明联左 QR 的「加密验证资讯」：AES-128/CBC/PKCS5，明文 = 发票号码 10 + 随机码 4，Base64 后恰为 24 字符。
 *
 * <p>密钥是营业人在财政部平台取得的 AES Key（32 位十六进制 = 16 字节）；IV 是财政部 QR 规格公开的固定值。
 */
public final class TwQrCipher {

    private static final byte[] IV = Base64.getDecoder().decode("Dt8lyToo17X/XkXaQvihuA==");

    private TwQrCipher() {
    }

    public static String verification(byte[] key, String fiscalNumber, String randomCode) {
        try {
            var cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(IV));
            byte[] out = cipher.doFinal((fiscalNumber + randomCode).getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES unavailable", e);
        }
    }

    /** 32 位十六进制 → 16 字节；格式不对返回 null，由调用方报错。 */
    public static byte[] parseKey(String hex) {
        if (hex == null || !hex.matches("[0-9A-Fa-f]{32}")) return null;
        byte[] out = new byte[16];
        for (int i = 0; i < 16; i++) out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        return out;
    }
}
