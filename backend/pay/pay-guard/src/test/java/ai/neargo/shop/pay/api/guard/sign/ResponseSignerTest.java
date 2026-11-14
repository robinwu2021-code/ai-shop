package ai.neargo.shop.pay.api.guard.sign;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.pay.openapp.ConfigAppSignatures;
import ai.neargo.shop.pay.openapp.entity.PayApp;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * 响应签名（开发计划 M3.4；API 清单 §1.2）：{@code Base64(HMAC(Timestamp\nNonce\nhex(SHA256(响应体))))}，
 * 用接入方当前那版 AppSecret（去掉 {@code sk_test_} 前缀）。向量 P2。
 */
class ResponseSignerTest {

    private static PayApp app() {
        PayApp a = new PayApp();
        a.setId(7L);
        a.setAppNo("APP7Q2K9XW");
        return a;
    }

    private static ConfigAppSignatures signer() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("shop.pay.open.apps.APP7Q2K9XW.hmac-secret", "sk_test_Zk3Qm9Lp2Xv7Rt5Wb8Nc4Hd6Jf1Gs0Ay");
        return new ConfigAppSignatures(env);
    }

    @Test
    @DisplayName("★★★ P2 向量：响应体 {} 的签名与 API 清单 §7 一致；头里带时间戳与 nonce（接入方验签要用）")
    void p2Vector() {
        Map<String, String> h = new ResponseSigner(signer()).sign(app(), "1761465601000", "5d2c9e7a1f3b4a6c8e0d2f4b6a8c0e1f",
                "{}".getBytes(StandardCharsets.UTF_8)).orElseThrow();

        assertThat(h).containsEntry("X-Signature", "umstQTPXokyyX02RxHKAeiNbQUc1VAAvHykNvWaInfQ=")
                .containsEntry("X-Timestamp", "1761465601000")
                .containsEntry("X-Nonce", "5d2c9e7a1f3b4a6c8e0d2f4b6a8c0e1f")
                .doesNotContainKey("X-Key-Serial");
    }

    @Test
    @DisplayName("★★ 待签串：时间戳、nonce、响应体 SHA-256 的 hex，换行分隔")
    void stringToSign() {
        assertThat(ResponseSigner.stringToSign("1", "n", "{}".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("1\nn\n44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a");
    }

    @Test
    @DisplayName("★★ 接入方没有可用密钥：不签（不拿别家的密钥凑一个签名）")
    void noKeyNoSignature() {
        PayApp other = app();
        other.setAppNo("APP-NO-KEY");
        assertThat(new ResponseSigner(signer()).sign(other, "1", "n", new byte[0])).isEmpty();
    }
}
