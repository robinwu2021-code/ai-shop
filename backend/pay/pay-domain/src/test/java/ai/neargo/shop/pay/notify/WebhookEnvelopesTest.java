package ai.neargo.shop.pay.notify;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.pay.notify.entity.PayBizNotify;
import ai.neargo.shop.pay.openapp.ConfigAppSignatures;
import ai.neargo.shop.pay.openapp.ConfigAppSignaturesTest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 推送加密与签名（开发计划 M3.6；TDD-支付服务-开放API加解密与密钥分发 §2.4 P4；API 清单 §3）：业务内容整体放进
 * {@code resource}，AES-256-GCM（EncryptKey），{@code nonce} 单独一个字段、每次新生成，AAD = {@code eventType|eventId}；
 * 签名同响应签名（P2）。接收方的解密与验签在本类里<b>独立实现一遍</b>。
 */
class WebhookEnvelopesTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 接收方：按 resource 的 nonce / associatedData / ciphertext 解（P4，独立实现） */
    static String receiverDecrypt(String encryptKey, JsonNode resource) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encryptKey.getBytes(StandardCharsets.UTF_8), "AES"),
                new GCMParameterSpec(128, Base64.getDecoder().decode(resource.get("nonce").asString())));
        c.updateAAD(resource.get("associatedData").asString().getBytes(StandardCharsets.UTF_8));
        return new String(c.doFinal(Base64.getDecoder().decode(resource.get("ciphertext").asString())),
                StandardCharsets.UTF_8);
    }

    /** 接收方验签（P2，独立实现）：HMAC(Timestamp\nNonce\nhex(SHA256(体))) */
    static String receiverSignature(String hmacKey, String ts, String nonce, String body) throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(hmacKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal((ts + "\n" + nonce + "\n" + hash).getBytes(StandardCharsets.UTF_8)));
    }

    private static PayBizNotify paymentSucceeded() {
        PayBizNotify n = new PayBizNotify();
        n.setId(1L);
        n.setEventType("PAYMENT_SUCCEEDED");
        n.setEventKey("PAYMENT_SUCCEEDED|O9|3");
        n.setStateVersion(3);
        n.setPayloadMasked("{\"eventId\":\"PAYMENT_SUCCEEDED|O9|3\",\"eventType\":\"PAYMENT_SUCCEEDED\",\"createdAt\":1,"
                + "\"data\":{\"payOrderNo\":\"PYO-9\",\"status\":\"SUCCEEDED\",\"paidMinor\":1200}}");
        return n;
    }

    private static WebhookEnvelopes envelopes(boolean withEncryptKey) {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("shop.pay.open.apps." + ConfigAppSignaturesTest.APP + ".hmac-secret", ConfigAppSignaturesTest.SECRET);
        if (withEncryptKey) {
            env.setProperty("shop.pay.open.apps." + ConfigAppSignaturesTest.APP + ".encrypt-key",
                    ConfigAppSignaturesTest.ENCRYPT_KEY);
        }
        ConfigAppSignatures keys = new ConfigAppSignatures(env);
        return new WebhookEnvelopes(keys, keys);
    }

    @Test
    @DisplayName("★★★ P4 向量：接收方按 nonce / associatedData / ciphertext 解得出 API 清单 §7 的明文（独立实现本身是对的）")
    void p4Vector() throws Exception {
        JsonNode resource = JSON.readTree("{\"nonce\":\"Dw4NDAsKCQgHBgUE\",\"associatedData\":\"payment.succeeded|EVT20261026000001\","
                + "\"ciphertext\":\"i6y9W9OYb/Ebq+KW8d0K0AKGhVL6Gytj2b7m1PdHZiEHtZziNWb9FWMoK51p4/8zRI+rV7RVFlkNG22WDiVt9bUuTikZw1x5FJNj00sHucvBKouVA9nWskgdhbnZijhhODpEMGqfsRtlyXu+1d0Gig2FPt5TL08SvdMd8tDLDgT5wbA7AIE=\"}");

        assertThat(receiverDecrypt(ConfigAppSignaturesTest.ENCRYPT_KEY, resource)).isEqualTo(
                "{\"payOrderNo\":\"P20261026000001\",\"outOrderNo\":\"M20261026001\",\"status\":\"SUCCEEDED\","
                        + "\"amountMinor\":12000,\"currency\":\"TWD\"}");
    }

    @Test
    @DisplayName("★★★ 有 EncryptKey：业务内容只在 resource 里（密文），接收方解得出 data；签名按 P2 验得过；明文不出现在体里")
    void encryptedAndSigned() throws Exception {
        WebhookEnvelopes.Envelope e = envelopes(true).build(paymentSucceeded(), ConfigAppSignaturesTest.app()).orElseThrow();

        JsonNode body = JSON.readTree(e.body());
        assertThat(body.has("data")).isFalse();
        assertThat(body.get("eventId").asString()).isEqualTo("PAYMENT_SUCCEEDED|O9|3");
        assertThat(body.get("stateVersion").asInt()).isEqualTo(3);
        JsonNode resource = body.get("resource");
        assertThat(resource.get("algorithm").asString()).isEqualTo("AEAD_AES_256_GCM");
        assertThat(resource.get("associatedData").asString()).isEqualTo("PAYMENT_SUCCEEDED|PAYMENT_SUCCEEDED|O9|3");
        assertThat(JSON.readTree(receiverDecrypt(ConfigAppSignaturesTest.ENCRYPT_KEY, resource)).get("payOrderNo").asString())
                .isEqualTo("PYO-9");
        assertThat(e.body()).doesNotContain("PYO-9");
        assertThat(e.headers().get("X-Signature")).isEqualTo(receiverSignature("Zk3Qm9Lp2Xv7Rt5Wb8Nc4Hd6Jf1Gs0Ay",
                e.headers().get("X-Timestamp"), e.headers().get("X-Nonce"), e.body()));
        assertThat(e.headers()).containsEntry("X-App-Id", ConfigAppSignaturesTest.APP).containsKey("X-Event-Id");
    }

    @Test
    @DisplayName("★★★ nonce 每次新生成：同一条推送连投两次，加密 nonce 与签名 nonce 都不一样")
    void nonceNeverRepeats() throws Exception {
        WebhookEnvelopes en = envelopes(true);
        WebhookEnvelopes.Envelope a = en.build(paymentSucceeded(), ConfigAppSignaturesTest.app()).orElseThrow();
        WebhookEnvelopes.Envelope b = en.build(paymentSucceeded(), ConfigAppSignaturesTest.app()).orElseThrow();

        assertThat(JSON.readTree(a.body()).get("resource").get("nonce").asString())
                .isNotEqualTo(JSON.readTree(b.body()).get("resource").get("nonce").asString());
        assertThat(a.headers().get("X-Nonce")).isNotEqualTo(b.headers().get("X-Nonce"));
    }

    @Test
    @DisplayName("★★ 过渡：接入方还没有 EncryptKey —— 体照旧带明文 data（推送内容本就只有掩码），签名同样按 P2")
    void withoutEncryptKeyKeepsPlainData() throws Exception {
        WebhookEnvelopes.Envelope e = envelopes(false).build(paymentSucceeded(), ConfigAppSignaturesTest.app()).orElseThrow();

        assertThat(JSON.readTree(e.body()).get("data").get("payOrderNo").asString()).isEqualTo("PYO-9");
        assertThat(e.headers().get("X-Signature")).isEqualTo(receiverSignature("Zk3Qm9Lp2Xv7Rt5Wb8Nc4Hd6Jf1Gs0Ay",
                e.headers().get("X-Timestamp"), e.headers().get("X-Nonce"), e.body()));
    }
}
