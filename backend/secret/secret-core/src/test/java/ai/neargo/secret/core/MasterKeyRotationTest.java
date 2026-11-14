package ai.neargo.secret.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.secret.api.KeyOwner;
import ai.neargo.secret.api.KeyPurpose;
import ai.neargo.secret.api.KeyRegistry.NewKey;
import ai.neargo.secret.api.KeyRegistry.NewVersion;
import ai.neargo.secret.api.SecretException;
import ai.neargo.secret.api.SecretNamespace;
import ai.neargo.secret.api.SecretRef;
import ai.neargo.secret.store.entity.SecMasterKey;
import ai.neargo.secret.store.entity.SecSecretBlob;
import ai.neargo.secret.store.mapper.SecretMappers;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** AC8：主密钥轮换（表结构说明 §3.7）—— 只重新包裹数据密钥，密文不动；旧主密钥无引用后退役 */
class MasterKeyRotationTest {

    @Test
    @DisplayName("★★★ 换主密钥：旧的转 DECRYPT_ONLY，任务逐条重新包裹，cipher_text 一字不变，撤掉旧主密钥后照样解得开")
    void rewrapMovesEveryBlobToTheNewMasterKey() {
        SecretFixture f = SecretFixture.withPayKey();
        SecretRef field = f.vault.seal(new KeyOwner(SecretNamespace.PAY, "ONBOARDING_MATERIAL", 1),
                "6222020200112233".getBytes(StandardCharsets.UTF_8));
        long keyId = f.registry.register(new NewKey(new KeyOwner(SecretNamespace.PAY, "PAY_MERCHANT", 5),
                KeyPurpose.OUTBOUND_ENCRYPT, "m5 apiv3", "WECHAT", "AES_256_GCM", "SYMMETRIC_SECRET", "ops"));
        var v1 = f.registry.addVersion(keyId, new NewVersion("apiv3-key-32-bytes-long-0000000".getBytes(
                StandardCharsets.UTF_8), null, "UPLOADED", null, null, null, "ops"));
        f.registry.markVerified(v1, "PAY:pay_channel_call_log:1", "ops");
        f.registry.enable(v1, "ops-2", null);
        var blobs = f.store.mapper(SecretMappers.SecretBlobMapper.class);
        String cipherBefore = blobs.selectById(field.blobId()).getCipherText();
        long oldMk = blobs.selectById(field.blobId()).getSecMasterKeyId();

        // 运维在部署环境注入新主密钥 b 并标为 ACTIVE，a 留着只解密 —— 一次重启
        f.addMasterKey("pay-sandbox-b", SecretNamespace.PAY, true);
        f.start();
        var masterKeys = f.store.mapper(SecretMappers.MasterKeyMapper.class);
        assertThat(masterKeys.selectById(oldMk).getStatus()).isEqualTo("DECRYPT_ONLY");

        var summary = f.lifecycle.runOnce();

        assertThat(summary.rewrapped()).isEqualTo(2);
        SecSecretBlob after = blobs.selectById(field.blobId());
        assertThat(after.getSecMasterKeyId()).isNotEqualTo(oldMk);
        assertThat(after.getCipherText()).as("密文不动，只换包裹").isEqualTo(cipherBefore);
        assertThat(after.getRewrappedAt()).isNotNull();
        assertThat(masterKeys.selectById(oldMk).getStatus()).as("无引用即退役").isEqualTo("RETIRED");
        assertThat(KeyRotationTest.eventTypes(f, keyId)).contains("REWRAPPED");

        f.dropMasterKey("pay-sandbox-a");
        f.start();
        assertThat(new String(f.vault.open(SecretNamespace.PAY, field), StandardCharsets.UTF_8))
                .isEqualTo("6222020200112233");
        assertThat(f.registry.materialOf(v1)).isNotEmpty();
        assertThat(masterKeys.selectCount(Wrappers.<SecMasterKey>lambdaQuery()
                .eq(SecMasterKey::getStatus, "ACTIVE"))).isEqualTo(1L);
    }

    @Test
    @DisplayName("★★ 没重新包裹就撤掉旧主密钥：解密报 NO_MASTER_KEY，而不是解出乱码")
    void droppingOldMasterKeyTooEarlyFailsLoudly() {
        SecretFixture f = SecretFixture.withPayKey();
        SecretRef field = f.vault.seal(new KeyOwner(SecretNamespace.PAY, "ONBOARDING_MATERIAL", 1), "x".getBytes());
        f.dropMasterKey("pay-sandbox-a");
        f.addMasterKey("pay-sandbox-b", SecretNamespace.PAY, true);
        f.start();

        assertThatThrownBy(() -> f.vault.open(SecretNamespace.PAY, field)).isInstanceOfSatisfying(
                SecretException.class, e -> assertThat(e.reason()).isEqualTo(SecretException.Reason.NO_MASTER_KEY));
    }
}
