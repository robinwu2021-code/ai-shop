package ai.neargo.shop.channel.notify.port;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link PushCrypto} 的签名正确性 —— 没有 Google/Apple 的真实链路，这里就是唯一
 * 能在离线下证明「签出来的 JWT 是对的」的地方（同 {@code NotifyLoggingPortTest} 的定位）。
 *
 * <p>最要命的一条是 {@link #es256IsJoseRawNotDer}：JDK 出 DER，而 APNs 要 JOSE 定长 R‖S。
 * 少了那步转换，本地看不出任何异常，上线后 Apple 一律回 403，且错误不提编码。
 */
@DisplayName("推送 JWT 签名")
class PushCryptoTest {

    @Test
    @DisplayName("★★★ RS256（FCM）：签名能被对应公钥验过")
    void rs256VerifiesWithPublicKey() throws Exception {
        KeyPair kp = keyPair("RSA", 2048);
        String signingInput = "header.claim";
        String sigB64 = PushCrypto.signRs256(signingInput, kp.getPrivate());

        Signature v = Signature.getInstance("SHA256withRSA");
        v.initVerify(kp.getPublic());
        v.update(signingInput.getBytes(StandardCharsets.UTF_8));
        assertThat(v.verify(Base64.getUrlDecoder().decode(sigB64)))
                .as("RS256 签名必须能被 RSA 公钥验过 —— 验不过 Google 回 invalid_grant")
                .isTrue();
        assertThat((RSAPublicKey) kp.getPublic()).isNotNull();
    }

    @Test
    @DisplayName("★★★ ES256（APNs）：输出是 JOSE 定长 64 字节 R‖S，不是 DER")
    void es256IsJoseRawNotDer() throws Exception {
        KeyPair kp = keyPair("EC", 256);
        String signingInput = "header.claim";
        byte[] raw = Base64.getUrlDecoder().decode(PushCrypto.signEs256(signingInput, kp.getPrivate()));

        assertThat(raw)
                .as("JOSE ES256 必须是定长 64 字节（R‖S 各 32）—— DER 变长会被 APNs 当成非法 token")
                .hasSize(64);

        // 把 JOSE raw 还原成 DER，再用公钥验一遍，证明签名本身也是对的
        byte[] der = joseToDer(raw);
        Signature v = Signature.getInstance("SHA256withECDSA");
        v.initVerify(kp.getPublic());
        v.update(signingInput.getBytes(StandardCharsets.UTF_8));
        assertThat(v.verify(der)).as("还原成 DER 后必须能被 EC 公钥验过").isTrue();
        assertThat((ECPublicKey) kp.getPublic()).isNotNull();
    }

    @Test
    @DisplayName("★★ PKCS#8 PEM 装载：容忍字面 \\n 与 PEM 头尾")
    void loadsPkcs8FromPemWithLiteralNewlines() throws Exception {
        PrivateKey key = keyPair("EC", 256).getPrivate();
        String base64 = Base64.getEncoder().encodeToString(key.getEncoded());
        // env 常把 .p8 塞成一行、换行写字面 \n
        String pem = "-----BEGIN PRIVATE KEY-----\\n" + base64 + "\\n-----END PRIVATE KEY-----";

        PrivateKey loaded = PushCrypto.loadPkcs8(pem, "EC");
        assertThat(loaded.getEncoded())
                .as("装载回来的私钥编码必须与原始一致").isEqualTo(key.getEncoded());
    }

    @Test
    @DisplayName("★★ base64url 无填充、URL 安全")
    void base64UrlHasNoPaddingAndIsUrlSafe() {
        String s = PushCrypto.base64Url("{\"alg\":\"ES256\"}?/+");
        assertThat(s).doesNotContain("=").doesNotContain("+").doesNotContain("/");
    }

    @Test
    @DisplayName("★ 坏 PEM 直接抛，不静默返回 null 私钥")
    void badPemThrows() {
        assertThatThrownBy(() -> PushCrypto.loadPkcs8("not-a-key", "RSA"))
                .isInstanceOf(IllegalStateException.class);
    }

    private static KeyPair keyPair(String algo, int param) throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance(algo);
        if ("EC".equals(algo)) {
            g.initialize(new ECGenParameterSpec("secp256r1"));
        } else {
            g.initialize(param);
        }
        return g.generateKeyPair();
    }

    /** JOSE 定长 R‖S(64) → DER SEQUENCE{INTEGER r, INTEGER s}，供验签用。 */
    private static byte[] joseToDer(byte[] raw) {
        byte[] r = derInt(new BigInteger(1, java.util.Arrays.copyOfRange(raw, 0, 32)));
        byte[] s = derInt(new BigInteger(1, java.util.Arrays.copyOfRange(raw, 32, 64)));
        byte[] out = new byte[2 + r.length + s.length];
        out[0] = 0x30;
        out[1] = (byte) (r.length + s.length);
        System.arraycopy(r, 0, out, 2, r.length);
        System.arraycopy(s, 0, out, 2 + r.length, s.length);
        return out;
    }

    private static byte[] derInt(BigInteger v) {
        byte[] b = v.toByteArray(); // BigInteger 已带符号位（正数高位置 1 时前导 0）
        byte[] out = new byte[2 + b.length];
        out[0] = 0x02;
        out[1] = (byte) b.length;
        System.arraycopy(b, 0, out, 2, b.length);
        return out;
    }
}
