package ai.neargo.shop.pay.secret;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.secret.api.KeyOwner;
import ai.neargo.secret.api.KeyPurpose;
import ai.neargo.secret.api.KeyRegistry;
import ai.neargo.secret.api.KeyVault;
import ai.neargo.secret.api.KeyVersionRef;
import ai.neargo.secret.api.SecretException;
import ai.neargo.secret.api.SecretNamespace;
import ai.neargo.secret.api.StorageTier;
import ai.neargo.shop.pay.openapp.AppSignatureVerifier.Verified;
import ai.neargo.shop.pay.openapp.AppSigner;
import ai.neargo.shop.pay.openapp.ConfigAppSignaturesTest;
import ai.neargo.shop.pay.openapp.entity.PayApp;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 接入方验签经 {@link KeyVault}（开发计划 M3.1；类清单 §3 {@code SecretAppSignatureVerifier}）：材料不离开密钥模块，
 * 只交版本引用去运算 —— L5（KMS / HSM）的 AppSecret 也能验。P1 向量：密钥材料是 AppSecret 去掉 {@code sk_test_} 的那段。
 */
class SecretAppSignaturesTest {

    private static final String P1_MATERIAL = "Zk3Qm9Lp2Xv7Rt5Wb8Nc4Hd6Jf1Gs0Ay";
    private static final String V2_MATERIAL = "new-material-0123456789abcdefgh";
    private static final String V0_MATERIAL = "revoked-material-0123456789ab";
    private static final long KEY = 107;
    private static final KeyVersionRef V1 = new KeyVersionRef(KEY, 1);
    private static final KeyVersionRef V2 = new KeyVersionRef(KEY, 2);
    /** 已吊销：按序列号找得到，但不在可验签的版本里 */
    private static final KeyVersionRef V0 = new KeyVersionRef(KEY, 3);

    /** 版本 → 材料；验签可接受 V2（ACTIVE）与 V1（重叠期 ROTATING） */
    private final Map<KeyVersionRef, String> material = Map.of(V2, V2_MATERIAL, V1, P1_MATERIAL, V0, V0_MATERIAL);
    private final Map<String, KeyVersionRef> serials = Map.of("S2", V2, "S1", V1, "S0", V0);
    private boolean vaultDown;

    private final KeyVault vault = new KeyVault() {
        @Override
        public byte[] mac(KeyVersionRef ref, byte[] data) {
            if (vaultDown) {
                throw new SecretException(SecretException.Reason.KEY_UNUSABLE, "down");
            }
            return hmac(material.get(ref), data);
        }

        @Override
        public byte[] sign(KeyVersionRef ref, byte[] data) {
            throw new UnsupportedOperationException();
        }

        @Override
        public byte[] encrypt(KeyVersionRef ref, byte[] plaintext, byte[] aad) {
            throw new UnsupportedOperationException();
        }

        @Override
        public byte[] decrypt(KeyVersionRef ref, byte[] ciphertext, byte[] aad) {
            throw new UnsupportedOperationException();
        }

        @Override
        public StorageTier tierOf(KeyVersionRef ref) {
            throw new UnsupportedOperationException();
        }
    };

    private final SecretAppSignatures signatures = new SecretAppSignatures(new StubRegistry(), vault);

    private static byte[] hmac(String key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sig(String key, String toSign) {
        return Base64.getEncoder().encodeToString(hmac(key, toSign.getBytes(StandardCharsets.UTF_8)));
    }

    private static PayApp app(String ownerType) {
        PayApp a = ConfigAppSignaturesTest.app();
        a.setOwnerType(ownerType);
        return a;
    }

    @Test
    @DisplayName("★★★ P1 向量：不带 X-Key-Serial 时逐把试（ACTIVE 在前），重叠期旧版本签的也认，并说出是哪一版")
    void p1VectorDuringOverlap() {
        String toSign = ConfigAppSignaturesTest.p1ToSign();

        Optional<Verified> v = signatures.verify(app("PARTNER"), null, toSign, ConfigAppSignaturesTest.P1);

        assertThat(v).hasValueSatisfying(x -> assertThat(x.keyVersion()).isEqualTo(1));
        assertThat(signatures.verify(app("PARTNER"), null, toSign, ConfigAppSignaturesTest.P1_WITH_PREFIX))
                .as("带前缀整串当密钥的反例").isEmpty();
    }

    @Test
    @DisplayName("★★★ 带 X-Key-Serial：只用那一版验，不再逐把试；不认识的序列号、已吊销的版本都验不过")
    void serialPinsTheVersion() {
        String toSign = "anything";
        String byV1 = sig(P1_MATERIAL, toSign);

        assertThat(signatures.verify(app("PARTNER"), "S1", toSign, byV1)).isPresent();
        assertThat(signatures.verify(app("PARTNER"), "S2", toSign, byV1)).as("指了 S2 就只认 S2").isEmpty();
        assertThat(signatures.verify(app("PARTNER"), "S9", toSign, byV1)).isEmpty();
        assertThat(signatures.verify(app("PARTNER"), "S0", toSign, sig(V0_MATERIAL, toSign)))
                .as("吊销版本按序列号找得到，但不在可验签的版本里").isEmpty();
    }

    @Test
    @DisplayName("★★ 内部系统找 INTERNAL_APP 的密钥；没有密钥、密钥服务出错 → 验不过，不抛、不透内部状态")
    void ownerTypeAndFailures() {
        String toSign = "x";
        assertThat(signatures.verify(app("INTERNAL"), null, toSign, sig(V2_MATERIAL, toSign)))
                .as("INTERNAL_APP 下没登记密钥").isEmpty();
        vaultDown = true;
        assertThat(signatures.verify(app("PARTNER"), null, toSign, sig(V2_MATERIAL, toSign))).isEmpty();
        assertThat(signatures.sign(app("PARTNER"), toSign.getBytes(StandardCharsets.UTF_8))).isEmpty();
    }

    @Test
    @DisplayName("★★★ 出站签名（推送）用当前版本（ACTIVE），说出版本号；签出来的自己验得过")
    void signsWithCurrentVersion() {
        byte[] data = "push-body".getBytes(StandardCharsets.UTF_8);

        AppSigner.Signature s = signatures.sign(app("PARTNER"), data).orElseThrow();

        assertThat(s.keyVersion()).isEqualTo(2);
        assertThat(s.base64()).isEqualTo(Base64.getEncoder().encodeToString(hmac(V2_MATERIAL, data)));
        assertThat(signatures.verify(app("PARTNER"), null, "push-body", s.base64())).isPresent();
    }

    /** 只实现签名用到的方法；其余不该被调到 */
    private final class StubRegistry implements KeyRegistry {

        @Override
        public Optional<KeyInfo> find(KeyOwner owner, KeyPurpose purpose) {
            if (!KeyPurpose.INBOUND_HMAC.equals(purpose)
                    || !owner.equals(new KeyOwner(SecretNamespace.PAY, "PARTNER_APP", 7))) {
                return Optional.empty();
            }
            return Optional.of(new KeyInfo(KEY, owner, purpose, "ACTIVE", 2, null));
        }

        @Override
        public List<KeyVersionRef> verificationVersions(long keyId) {
            return keyId == KEY ? List.of(V2, V1) : List.of();
        }

        @Override
        public Optional<KeyVersionRef> findVersion(long keyId, String externalKeyId) {
            return keyId == KEY ? Optional.ofNullable(serials.get(externalKeyId)) : Optional.empty();
        }

        @Override
        public KeyVersionRef current(long keyId) {
            return V2;
        }

        @Override
        public long register(NewKey key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public KeyVersionRef addVersion(long keyId, NewVersion version) {
            throw new UnsupportedOperationException();
        }

        @Override
        public IssuedSecret generate(long keyId, int lengthBytes, String issuedBy, String reason) {
            throw new UnsupportedOperationException();
        }

        @Override
        public IssuedSecret generate(long keyId, Generation generation) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void markVerified(KeyVersionRef ref, String evidence, String operator) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void enable(KeyVersionRef ref, String operator, String approvalRef) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void revoke(KeyVersionRef ref, String operator, String reason) {
            throw new UnsupportedOperationException();
        }

        @Override
        public byte[] materialOf(KeyVersionRef ref) {
            throw new UnsupportedOperationException("材料不该离开密钥模块");
        }

        @Override
        public String fingerprintOf(KeyVersionRef ref) {
            throw new UnsupportedOperationException();
        }
    }
}
