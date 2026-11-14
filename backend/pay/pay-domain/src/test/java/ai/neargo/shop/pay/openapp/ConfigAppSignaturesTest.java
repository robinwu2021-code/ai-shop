package ai.neargo.shop.pay.openapp;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.pay.openapp.entity.PayApp;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * 试点过渡：接入方密钥在部署配置里（开发计划 M3.1；类清单 §3 {@code ConfigAppSignatureVerifier}）。
 * 用 API 清单 §7 的 P1 向量验：AppSecret 的 {@code sk_test_} / {@code sk_live_} 前缀不参与 HMAC。
 */
public class ConfigAppSignaturesTest {

    public static final String APP = "APP7Q2K9XW";
    public static final String SECRET = "sk_test_Zk3Qm9Lp2Xv7Rt5Wb8Nc4Hd6Jf1Gs0Ay";
    public static final String P1 = "nsNjeNX+z0xMF/hIgsbFLBkbDWOFBAIYwMnKD+fQVxU=";
    /** 反例：把带前缀的整串当 HMAC 密钥算出来的签名 —— 平台必须拒 */
    public static final String P1_WITH_PREFIX = "COTZmHskHe/MoBidbfalyUR4Qx96IXyeiRbFmEZboFA=";

    /** API 清单 §7 P1 的待签串 */
    public static String p1ToSign() {
        String body = "{\"outOrderNo\":\"M20261026001\",\"amountMinor\":12000,\"currency\":\"TWD\",\"payMethod\":\"LINEPAY\"}";
        String bodyHash = SignatureCanonicalizer.sha256Hex(body.getBytes(StandardCharsets.UTF_8));
        assertThat(bodyHash).isEqualTo("a5c9b0a01becc5121d926a7d702ff9e6d0d335367a53b9787e6e481d37bd9a35");
        return SignatureCanonicalizer.stringToSign("POST", "/open/pay/v1/orders", Map.<String, List<String>>of(), APP,
                "1761465600000", "8c1f0e2a5b7d4c3e9f60a1b2c3d4e5f6", bodyHash);
    }

    public static PayApp app() {
        PayApp a = new PayApp();
        a.setId(7L);
        a.setAppNo(APP);
        return a;
    }

    private static ConfigAppSignatures withSecrets(String current, String next) {
        MockEnvironment env = new MockEnvironment();
        if (current != null) {
            env.setProperty("shop.pay.open.apps." + APP + ".hmac-secret", current);
        }
        if (next != null) {
            env.setProperty("shop.pay.open.apps." + APP + ".hmac-secret-next", next);
        }
        return new ConfigAppSignatures(env);
    }

    @Test
    @DisplayName("★★★ P1：配置里写的是带前缀的整串 AppSecret，验签用去掉前缀的那段；用整串签的反例被拒")
    void p1VectorAndPrefixCounterExample() {
        ConfigAppSignatures sigs = withSecrets(SECRET, null);

        assertThat(sigs.verify(app(), null, p1ToSign(), P1)).isPresent();
        assertThat(sigs.verify(app(), null, p1ToSign(), P1_WITH_PREFIX)).isEmpty();
    }

    @Test
    @DisplayName("★★ 配置里只写了随机段（老写法）也认；轮换期 next 那把也能验")
    void bareSecretAndRotation() {
        assertThat(withSecrets("Zk3Qm9Lp2Xv7Rt5Wb8Nc4Hd6Jf1Gs0Ay", null).verify(app(), null, p1ToSign(), P1)).isPresent();
        assertThat(withSecrets("some-old-secret-0123456789", SECRET).verify(app(), null, p1ToSign(), P1)).isPresent();
        assertThat(withSecrets(null, null).verify(app(), null, p1ToSign(), P1)).as("没配密钥 = 验不过").isEmpty();
    }

    @Test
    @DisplayName("★★ 签名不是合法 Base64、长度不对：验不过，不抛")
    void malformedSignature() {
        ConfigAppSignatures sigs = withSecrets(SECRET, null);
        assertThat(sigs.verify(app(), null, p1ToSign(), "%%%")).isEmpty();
        assertThat(sigs.verify(app(), null, p1ToSign(), "AAAA")).isEmpty();
        assertThat(sigs.verify(app(), null, p1ToSign(), null)).isEmpty();
    }

    @Test
    @DisplayName("★★★ 出站签名（推送）用当前那把（不是 next），且与入站同一套 —— 自己签的自己验得过")
    void signsWithTheCurrentSecret() {
        ConfigAppSignatures sigs = withSecrets(SECRET, "next-secret-0123456789abcdef");
        String toSign = p1ToSign();

        AppSigner.Signature s = sigs.sign(app(), toSign.getBytes(StandardCharsets.UTF_8)).orElseThrow();

        assertThat(s.base64()).isEqualTo(P1);
        assertThat(withSecrets(null, null).sign(app(), toSign.getBytes(StandardCharsets.UTF_8))).isEmpty();
    }

    /** API 清单 §7 的 EncryptKey 与 P3 向量 */
    public static final String ENCRYPT_KEY = "Pa7Ve2Ky9Wq4Zm6Tn3Lr8Hs5Dc1Fb0Gx";
    public static final String P3_CIPHER = "obLD1OX2BxgpOktc59/SAi1E7uW27KsqhePcgjLC58SwEbcblEo=";
    public static final String P3_AAD = APP + "|refundAccount.accountNo";

    @Test
    @DisplayName("★★★ P3 向量（M3.5）：EncryptKey 的 UTF-8 字节作 AES-256-GCM 密钥，AAD = AppId|字段路径，解出 8123004567；字段路径不对解不开")
    void p3Vector() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("shop.pay.open.apps." + APP + ".encrypt-key", ENCRYPT_KEY);
        ConfigAppSignatures keys = new ConfigAppSignatures(env);
        byte[] packed = java.util.Base64.getDecoder().decode(P3_CIPHER);

        assertThat(keys.decrypt(app(), null, packed, P3_AAD.getBytes(StandardCharsets.UTF_8)))
                .hasValueSatisfying(b -> assertThat(new String(b, StandardCharsets.UTF_8)).isEqualTo("8123004567"));
        assertThat(keys.decrypt(app(), null, packed, (APP + "|refundAccount.accountName").getBytes(StandardCharsets.UTF_8)))
                .isEmpty();
        assertThat(withSecrets(SECRET, null).decrypt(app(), null, packed, P3_AAD.getBytes(StandardCharsets.UTF_8)))
                .as("没配 EncryptKey").isEmpty();
    }
}
