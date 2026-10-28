package ai.neargo.shop.pay.api.guard.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.openapp.ConfigAppSignatures;
import ai.neargo.shop.pay.openapp.entity.PayApp;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * 敏感字段解密（开发计划 M3.5；TDD-支付服务-开放API加解密与密钥分发 §2.4 P3 · §2.7）：
 * 标了 x-sensitive 的字段必须是密文，在守卫 ⑨ 解成明文交给 controller；明文被拒，字段被挪到别的路径解不开。
 */
class SensitiveFieldDecryptorTest {

    /** API 清单 §7：AppId、EncryptKey 与 P3 向量（AAD = APP7Q2K9XW|refundAccount.accountNo，明文 8123004567） */
    private static final String APP = "APP7Q2K9XW";
    private static final String ENCRYPT_KEY = "Pa7Ve2Ky9Wq4Zm6Tn3Lr8Hs5Dc1Fb0Gx";
    private static final String P3_CIPHER = "obLD1OX2BxgpOktc59/SAi1E7uW27KsqhePcgjLC58SwEbcblEo=";

    private final SensitiveFieldDecryptor decryptor;

    private static PayApp app() {
        PayApp a = new PayApp();
        a.setId(7L);
        a.setAppNo(APP);
        return a;
    }

    SensitiveFieldDecryptorTest() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("shop.pay.open.apps." + APP + ".encrypt-key",
                ENCRYPT_KEY);
        decryptor = new SensitiveFieldDecryptor(new ConfigAppSignatures(env));
    }

    private SensitiveFieldDecryptor.Outcome run(String action, String json) {
        return decryptor.decrypt(app(), action, null, json.getBytes(StandardCharsets.UTF_8));
    }

    private static String refund(String field, String value) {
        return "{\"outRefundNo\":\"RF1\",\"amountMinor\":100,\"refundAccount\":{\"" + field + "\":\"" + value + "\"}}";
    }

    @Test
    @DisplayName("★★★ P3：退款收款账号的密文在 ⑨ 解成明文，其余字段原样")
    void decryptsInPlace() {
        SensitiveFieldDecryptor.Outcome o = run("REQUEST_REFUND", refund("accountNo", P3_CIPHER));

        assertThat(o.rejection()).isNull();
        String body = new String(o.body(), StandardCharsets.UTF_8);
        assertThat(body).contains("\"accountNo\":\"8123004567\"").contains("\"outRefundNo\":\"RF1\"")
                .doesNotContain(P3_CIPHER);
    }

    @Test
    @DisplayName("★★★ 敏感字段传了明文 → 400 PAY_OPEN_FIELD_ENCRYPT_REQUIRED（不解析就放过 = 明文过 TLS 之外那一层）")
    void plaintextRejected() {
        SensitiveFieldDecryptor.Outcome o = run("REQUEST_REFUND", refund("accountNo", "8123004567"));

        assertThat(o.rejection()).isNotNull();
        assertThat(o.rejection().status()).isEqualTo(400);
        assertThat(o.rejection().code()).isEqualTo(ErrorCode.PAY_OPEN_FIELD_ENCRYPT_REQUIRED);
        assertThat(o.rejection().reason()).contains("refundAccount.accountNo");
    }

    @Test
    @DisplayName("★★★ 密文被挪到别的字段（AAD 带字段路径）→ 400 PAY_OPEN_FIELD_DECRYPT_FAILED，不分哪一步")
    void swappedPathFails() {
        SensitiveFieldDecryptor.Outcome o = run("REQUEST_REFUND", refund("accountName", P3_CIPHER));

        assertThat(o.rejection()).isNotNull();
        assertThat(o.rejection().code()).isEqualTo(ErrorCode.PAY_OPEN_FIELD_DECRYPT_FAILED);
    }

    @Test
    @DisplayName("★★ 没有敏感字段的动作、没传敏感字段、请求体不是 JSON：原样放行（后者交给 controller 回 400）")
    void untouchedCases() {
        String query = "{\"a\":1}";
        assertThat(run("QUERY", query).body()).isEqualTo(query.getBytes(StandardCharsets.UTF_8));
        assertThat(run("REQUEST_REFUND", "{\"outRefundNo\":\"RF1\"}").rejection()).isNull();
        assertThat(run("CREATE_ONLINE", "{not json").rejection()).isNull();
    }

    @Test
    @DisplayName("★★ 字段清单：建单的付款人三项、退款的收款账号三项")
    void registry() {
        assertThat(SensitiveFieldDecryptor.FIELDS.get("CREATE_ONLINE"))
                .containsExactlyInAnyOrder("payer.phone", "payer.email", "payer.name");
        assertThat(SensitiveFieldDecryptor.FIELDS.get("REQUEST_REFUND"))
                .containsExactlyInAnyOrder("refundAccount.bankCode", "refundAccount.accountNo", "refundAccount.accountName");
    }
}
