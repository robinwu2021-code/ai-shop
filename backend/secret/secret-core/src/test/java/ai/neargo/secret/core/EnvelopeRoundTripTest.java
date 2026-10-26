package ai.neargo.secret.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.secret.api.KeyOwner;
import ai.neargo.secret.api.SecretException;
import ai.neargo.secret.api.SecretNamespace;
import ai.neargo.secret.api.SecretRef;
import ai.neargo.secret.store.entity.SecSecretBlob;
import ai.neargo.secret.store.mapper.SecretMappers;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** AC1–AC3：信封加密往返、附加认证数据绑定行、命名空间与环境隔离 */
class EnvelopeRoundTripTest {

    private static final byte[] ID_NO = "A123456789".getBytes(StandardCharsets.UTF_8);
    private static final KeyOwner MATERIAL_7 = new KeyOwner(SecretNamespace.PAY, "ONBOARDING_MATERIAL", 7);

    @Test
    @DisplayName("★★★ 封存再解开得到原文；库里只有密文，没有明文、没有数据密钥原文")
    void sealThenOpenRoundTrips() {
        SecretFixture f = SecretFixture.withPayKey();

        SecretRef ref = f.vault.seal(MATERIAL_7, ID_NO);

        assertThat(f.vault.open(SecretNamespace.PAY, ref)).isEqualTo(ID_NO);
        SecSecretBlob row = f.store.mapper(SecretMappers.SecretBlobMapper.class).selectById(ref.blobId());
        assertThat(row.getAlgorithm()).isEqualTo("AES-256-GCM");
        assertThat(row.getPurpose()).isEqualTo("SENSITIVE_FIELD");
        assertThat(row.getOwnerType()).isEqualTo("ONBOARDING_MATERIAL");
        assertThat(row.getOwnerId()).isEqualTo(7L);
        assertThat(row.getEnv()).isEqualTo("SANDBOX");
        assertThat(row.getAadSha256()).hasSize(64);
        assertThat(row.getSecMasterKeyId()).isPositive();
        String stored = row.getCipherText() + row.getDataKeyCipher();
        assertThat(stored).doesNotContain("A123456789")
                .doesNotContain(Base64.getEncoder().encodeToString(ID_NO));
        // 同一明文封两次，密文不同（每段一把随机数据密钥 + 随机 IV）
        SecretRef again = f.vault.seal(MATERIAL_7, ID_NO);
        assertThat(f.store.mapper(SecretMappers.SecretBlobMapper.class).selectById(again.blobId()).getCipherText())
                .isNotEqualTo(row.getCipherText());
    }

    @Test
    @DisplayName("★★★ 密文挪到别的行（换归属）即解密失败 —— 附加认证数据绑定 id + 归属 + 环境")
    void ciphertextMovedToAnotherRowFails() {
        SecretFixture f = SecretFixture.withPayKey();
        SecretRef victim = f.vault.seal(MATERIAL_7, ID_NO);
        SecretRef attacker = f.vault.seal(new KeyOwner(SecretNamespace.PAY, "ONBOARDING_MATERIAL", 8), "x".getBytes());
        var blobs = f.store.mapper(SecretMappers.SecretBlobMapper.class);
        SecSecretBlob src = blobs.selectById(victim.blobId());
        // 把受害行的密文、包裹后的数据密钥原样搬到攻击者的行；aad_sha256 不是秘密，攻击者能按自己的行算对 ——
        // 所以拦住它的只能是 GCM 的附加认证数据，而不是摘要比对
        blobs.update(null, Wrappers.<SecSecretBlob>lambdaUpdate()
                .set(SecSecretBlob::getCipherText, src.getCipherText())
                .set(SecSecretBlob::getDataKeyCipher, src.getDataKeyCipher())
                .set(SecSecretBlob::getAadSha256, EnvelopeCipher.sha256Hex(
                        EnvelopeCipher.aad(attacker.blobId(), "PAY", "ONBOARDING_MATERIAL", 8, "SANDBOX")))
                .eq(SecSecretBlob::getId, attacker.blobId()));

        assertThatThrownBy(() -> f.vault.open(SecretNamespace.PAY, attacker))
                .isInstanceOfSatisfying(SecretException.class,
                        e -> assertThat(e.reason()).isEqualTo(SecretException.Reason.INTEGRITY));
        // 改归属 id 同样解不开
        blobs.update(null, Wrappers.<SecSecretBlob>lambdaUpdate()
                .set(SecSecretBlob::getOwnerId, 9L)
                .set(SecSecretBlob::getAadSha256, EnvelopeCipher.sha256Hex(
                        EnvelopeCipher.aad(victim.blobId(), "PAY", "ONBOARDING_MATERIAL", 9, "SANDBOX")))
                .eq(SecSecretBlob::getId, victim.blobId()));
        assertThatThrownBy(() -> f.vault.open(SecretNamespace.PAY, victim))
                .isInstanceOfSatisfying(SecretException.class,
                        e -> assertThat(e.reason()).isEqualTo(SecretException.Reason.INTEGRITY));
    }

    @Test
    @DisplayName("★★ 进程只能解本命名空间；没有本域主密钥就封不了")
    void namespaceIsolation() {
        SecretFixture f = SecretFixture.withPayKey();
        SecretRef ref = f.vault.seal(MATERIAL_7, ID_NO);

        assertThatThrownBy(() -> f.vault.open(SecretNamespace.LOGISTICS, ref))
                .isInstanceOfSatisfying(SecretException.class,
                        e -> assertThat(e.reason()).isEqualTo(SecretException.Reason.NAMESPACE_MISMATCH));
        assertThatThrownBy(() -> f.vault.seal(new KeyOwner(SecretNamespace.LOGISTICS, "PROVIDER", 1), ID_NO))
                .isInstanceOfSatisfying(SecretException.class,
                        e -> assertThat(e.reason()).isEqualTo(SecretException.Reason.NO_MASTER_KEY));
    }

    @Test
    @DisplayName("★★ 进程没配环境就拒绝加解密 —— 不猜沙箱还是生产")
    void noEnvRefuses() {
        SecretFixture f = new SecretFixture(null).start();

        assertThatThrownBy(() -> f.vault.seal(MATERIAL_7, ID_NO))
                .isInstanceOfSatisfying(SecretException.class,
                        e -> assertThat(e.reason()).isEqualTo(SecretException.Reason.ENV_NOT_CONFIGURED));
    }

    @Test
    @DisplayName("★★ 沙箱进程解不开生产密文（即使同一个库）")
    void sandboxCannotOpenProduction() {
        SecretFixture prod = new SecretFixture("PRODUCTION");
        prod.addMasterKey("pay-prod-a", SecretNamespace.PAY, true);
        prod.start();
        SecretRef ref = prod.vault.seal(MATERIAL_7, ID_NO);

        // 同一个库，换成沙箱进程：它只有沙箱主密钥
        SecretFixture sandbox = prod.restartAs("SANDBOX");
        sandbox.addMasterKey("pay-sandbox-a", SecretNamespace.PAY, true);
        sandbox.start();

        assertThatThrownBy(() -> sandbox.vault.open(SecretNamespace.PAY, ref))
                .isInstanceOfSatisfying(SecretException.class,
                        e -> assertThat(e.reason()).isEqualTo(SecretException.Reason.NAMESPACE_MISMATCH));
    }
}
