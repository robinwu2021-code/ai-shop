package ai.neargo.shop.channel.notify.port;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * 推送直连用的 JWT 签名（FCM 的 RS256、APNs 的 ES256），纯 JDK。
 *
 * <p><b>不引 JWT / JOSE 库</b>：与 {@link GetuiPushGateway} 同一条约束——本项目一律
 * {@code mvn -o} 离线构建。用到的只有 base64url、PKCS#8 私钥装载、一次签名，JDK 自带够用。
 *
 * <p><b>ES256 的坑单独说</b>：JDK 的 {@code SHA256withECDSA} 出的是 DER 编码，
 * 而 JOSE（APNs 要的）要的是**定长 64 字节 R‖S**。少了这步转换，Apple 直接回 403
 * InvalidProviderToken，而错误信息不会告诉你是编码问题——见 {@link #esDerToJose}。
 */
final class PushCrypto {

    private PushCrypto() {
    }

    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

    /** base64url（无填充）。JWT 三段都用它。 */
    static String base64Url(byte[] bytes) {
        return URL.encodeToString(bytes);
    }

    static String base64Url(String s) {
        return base64Url(s.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 从 PEM 装载 PKCS#8 私钥。
     *
     * <p>容忍环境变量里把换行写成字面 {@code \n}（.p8 / service-account 私钥常这么塞进
     * env）：先把 {@code \\n} 还原成真换行，再剥掉 PEM 头尾与所有空白，只留 base64。
     *
     * @param algorithm {@code "RSA"}（FCM service account）/ {@code "EC"}（APNs .p8）
     */
    static PrivateKey loadPkcs8(String pem, String algorithm) {
        try {
            String base64 = pem.replace("\\n", "\n")
                    .replaceAll("-----BEGIN [A-Z ]+-----", "")
                    .replaceAll("-----END [A-Z ]+-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(base64);
            return KeyFactory.getInstance(algorithm)
                    .generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (RuntimeException | java.security.GeneralSecurityException e) {
            throw new IllegalStateException("推送私钥装载失败（" + algorithm + "）：" + e.getMessage(), e);
        }
    }

    /** RS256 签名（FCM OAuth2 断言）。返回 base64url。 */
    static String signRs256(String signingInput, PrivateKey rsaKey) {
        return base64Url(sign("SHA256withRSA", signingInput, rsaKey));
    }

    /** ES256 签名（APNs provider token）。已把 DER 转成 JOSE 定长 R‖S。返回 base64url。 */
    static String signEs256(String signingInput, PrivateKey ecKey) {
        return base64Url(esDerToJose(sign("SHA256withECDSA", signingInput, ecKey)));
    }

    private static byte[] sign(String algo, String signingInput, PrivateKey key) {
        try {
            Signature sig = Signature.getInstance(algo);
            sig.initSign(key);
            sig.update(signingInput.getBytes(StandardCharsets.UTF_8));
            return sig.sign();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("推送 JWT 签名失败（" + algo + "）：" + e.getMessage(), e);
        }
    }

    /**
     * DER ECDSA 签名 → JOSE 定长 R‖S（P-256 各 32 字节，共 64）。
     *
     * <p>DER 是 {@code SEQUENCE { INTEGER r, INTEGER s }}，两个整数各自变长、可能带一个
     * 前导 0x00（表示正数）。JOSE 要的是去掉符号位、左侧补零到定长的裸拼接。
     */
    static byte[] esDerToJose(byte[] der) {
        // 结构：0x30 len 0x02 rLen <r> 0x02 sLen <s>
        int i = 2;
        if ((der[1] & 0x80) != 0) {
            i += der[1] & 0x7f; // 长度用了长格式（本场景不会，稳妥起见）
        }
        i++; // 跳过 r 的 0x02
        int rLen = der[i++];
        byte[] r = new byte[rLen];
        System.arraycopy(der, i, r, 0, rLen);
        i += rLen;
        i++; // 跳过 s 的 0x02
        int sLen = der[i++];
        byte[] s = new byte[sLen];
        System.arraycopy(der, i, s, 0, sLen);

        byte[] out = new byte[64];
        copyRightAligned(trimLeadingZero(r), out, 0, 32);
        copyRightAligned(trimLeadingZero(s), out, 32, 32);
        return out;
    }

    /** 去掉 DER 正整数可能的前导 0x00 符号字节。 */
    private static byte[] trimLeadingZero(byte[] b) {
        int start = 0;
        while (start < b.length - 1 && b[start] == 0) {
            start++;
        }
        if (start == 0) {
            return b;
        }
        byte[] out = new byte[b.length - start];
        System.arraycopy(b, start, out, 0, out.length);
        return out;
    }

    /** 把 src 右对齐放进 dst[offset, offset+len)，左侧补零。src 不能超过 len。 */
    private static void copyRightAligned(byte[] src, byte[] dst, int offset, int len) {
        if (src.length > len) {
            throw new IllegalStateException("ECDSA 分量超过 " + len + " 字节：曲线不是 P-256？");
        }
        System.arraycopy(src, 0, dst, offset + (len - src.length), src.length);
    }
}
