package ai.neargo.secret.core;

import ai.neargo.secret.api.SecretFormat;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/** 按 {@link SecretFormat} 生成材料：返回可打印字符的 ASCII 字节，这串字符本身就是密钥 */
final class SecretFormats {

    private static final char[] ALNUM =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    /** 62 × 4：随机字节落在 [0, 248) 才取用，否则重抽 —— 直接取模会让前 8 个字符多出 1/32 的概率 */
    private static final int ALNUM_BOUND = ALNUM.length * 4;

    private SecretFormats() {
    }

    static byte[] generate(SecretFormat format, SecureRandom random) {
        return switch (format) {
            case ALNUM_32 -> alnum(32, random);
            case BASE64URL_32B -> base64Url(32, random);
        };
    }

    /** W5 起的形态：{@code lengthBytes} 字节随机数的 Base64URL（无填充） */
    static byte[] base64Url(int lengthBytes, SecureRandom random) {
        byte[] raw = new byte[lengthBytes];
        random.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw).getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] alnum(int length, SecureRandom random) {
        byte[] out = new byte[length];
        byte[] one = new byte[1];
        int i = 0;
        while (i < length) {
            random.nextBytes(one);
            int b = one[0] & 0xFF;
            if (b < ALNUM_BOUND) {
                out[i++] = (byte) ALNUM[b % ALNUM.length];
            }
        }
        return out;
    }
}
