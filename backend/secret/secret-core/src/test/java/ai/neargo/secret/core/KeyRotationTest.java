package ai.neargo.secret.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.secret.api.KeyOwner;
import ai.neargo.secret.api.KeyPurpose;
import ai.neargo.secret.api.KeyRegistry.NewKey;
import ai.neargo.secret.api.KeyRegistry.NewVersion;
import ai.neargo.secret.api.KeyVersionRef;
import ai.neargo.secret.api.SecretException;
import ai.neargo.secret.api.SecretNamespace;
import ai.neargo.secret.store.entity.SecAccessLog;
import ai.neargo.secret.store.entity.SecKey;
import ai.neargo.secret.store.entity.SecKeyEvent;
import ai.neargo.secret.store.entity.SecKeyVersion;
import ai.neargo.secret.store.mapper.SecretMappers;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** AC4–AC7：版本生命周期、两人控制、轮换重叠期、到期任务、吊销、发放只显示一次 */
class KeyRotationTest {

    private static final KeyOwner APP_3 = new KeyOwner(SecretNamespace.PAY, "PARTNER_APP", 3);
    private static final byte[] V1 = "v1-hmac-secret-0001".getBytes(StandardCharsets.UTF_8);
    private static final byte[] V2 = "v2-hmac-secret-0002".getBytes(StandardCharsets.UTF_8);

    private static long appKey(SecretFixture f) {
        return f.registry.register(new NewKey(APP_3, KeyPurpose.INBOUND_HMAC, "partner 3 inbound", null,
                "HMAC_SHA256", "SYMMETRIC_SECRET", "ops-alice"));
    }

    private static KeyVersionRef upload(SecretFixture f, long keyId, byte[] material, String by) {
        return f.registry.addVersion(keyId, new NewVersion(material, null, "UPLOADED", null, null, null, by));
    }

    @Test
    @DisplayName("★★★ 轮换：v2 启用后 v1 进重叠期仍可验签，重叠期满任务把它退役，冻结 v1 的单据仍能取材料")
    void rotationOverlapThenRetire() {
        SecretFixture f = SecretFixture.withPayKey();
        long keyId = appKey(f);
        assertThat(f.registry.register(new NewKey(APP_3, KeyPurpose.INBOUND_HMAC, "dup", null,
                "HMAC_SHA256", "SYMMETRIC_SECRET", "ops-bob"))).as("同归属同用途同环境只有一把").isEqualTo(keyId);

        KeyVersionRef v1 = upload(f, keyId, V1, "ops-alice");
        f.registry.markVerified(v1, "PAY:pay_api_request_log:1", "ops-alice");
        f.registry.enable(v1, "ops-bob", "PAY:pay_approval:1");
        assertThat(f.registry.current(keyId)).isEqualTo(v1);
        assertThat(verifiable(f, keyId)).containsExactly(V1);

        KeyVersionRef v2 = upload(f, keyId, V2, "ops-alice");
        f.registry.markVerified(v2, "PAY:pay_api_request_log:2", "ops-alice");
        f.registry.enable(v2, "ops-bob", "PAY:pay_approval:2");

        assertThat(v2.versionIndex()).isEqualTo(2);
        assertThat(f.registry.current(keyId)).isEqualTo(v2);
        assertThat(versionRow(f, v1).getStatus()).isEqualTo("ROTATING");
        assertThat(versionRow(f, v1).getOverlapUntil()).isNotNull();
        assertThat(verifiable(f, keyId)).as("重叠期两把都能验").containsExactly(V2, V1);
        assertThat(f.registry.find(APP_3, KeyPurpose.INBOUND_HMAC).orElseThrow().currentVersion()).isEqualTo(2);

        f.clock.advance(Duration.ofDays(8));   // 默认策略重叠期 7 天
        assertThat(verifiable(f, keyId)).as("过了重叠期，任务还没跑也不再接受").containsExactly(V2);
        var summary = f.lifecycle.runOnce();

        assertThat(summary.retired()).isEqualTo(1);
        assertThat(versionRow(f, v1).getStatus()).isEqualTo("RETIRED");
        assertThat(f.registry.materialOf(v1)).as("冻结了 v1 的退款 / 查单仍可用").isEqualTo(V1);
        assertThat(eventTypes(f, keyId)).containsSubsequence("CREATED", "MATERIAL_ADDED", "VERIFIED", "ENABLED",
                "MATERIAL_ADDED", "VERIFIED", "ROTATION_STARTED", "ENABLED", "RETIRED");
        assertThat(f.lifecycle.runOnce().retired()).as("第二轮不重复退役").isZero();
    }

    @Test
    @DisplayName("★★★ 两人控制：录入人自己启用被拒；未验证的版本不能启用")
    void dualControlAndVerifyBeforeEnable() {
        SecretFixture f = SecretFixture.withPayKey();
        long keyId = appKey(f);
        KeyVersionRef v1 = upload(f, keyId, V1, "ops-alice");

        assertThatThrownBy(() -> f.registry.enable(v1, "ops-bob", null))
                .as("策略默认 verify_before_enable").isInstanceOfSatisfying(SecretException.class,
                        e -> assertThat(e.reason()).isEqualTo(SecretException.Reason.ILLEGAL_STATE));
        f.registry.markVerified(v1, "PAY:pay_api_request_log:1", "ops-alice");
        assertThatThrownBy(() -> f.registry.enable(v1, "ops-alice", null))
                .isInstanceOfSatisfying(SecretException.class,
                        e -> assertThat(e.reason()).isEqualTo(SecretException.Reason.DUAL_CONTROL));
        assertThat(versionRow(f, v1).getStatus()).isEqualTo("VERIFIED");
        assertThatThrownBy(() -> f.registry.current(keyId)).isInstanceOf(SecretException.class);
    }

    @Test
    @DisplayName("★★★ 吊销立即失效：新调用、验签、冻结版本取材料一律失败，不自动换版本")
    void revokeIsImmediate() {
        SecretFixture f = SecretFixture.withPayKey();
        long keyId = appKey(f);
        KeyVersionRef v1 = upload(f, keyId, V1, "ops-alice");
        f.registry.markVerified(v1, "e", "ops-alice");
        f.registry.enable(v1, "ops-bob", null);

        f.registry.revoke(v1, "ops-carol", "leaked in a ticket");

        assertThatThrownBy(() -> f.registry.materialOf(v1)).isInstanceOfSatisfying(SecretException.class,
                e -> assertThat(e.reason()).isEqualTo(SecretException.Reason.VERSION_UNUSABLE));
        assertThatThrownBy(() -> f.registry.current(keyId)).isInstanceOf(SecretException.class);
        assertThat(verifiable(f, keyId)).isEmpty();
        SecKeyVersion row = versionRow(f, v1);
        assertThat(row.getRevokedBy()).isEqualTo("ops-carol");
        assertThat(row.getActiveKey()).isNull();
    }

    @Test
    @DisplayName("★★ 到期任务：到期前 warn_before_days 记一次 EXPIRING_SOON，到期后密钥 EXPIRED、新调用失败")
    void expiryWarnsOnceThenExpires() {
        SecretFixture f = SecretFixture.withPayKey();
        long keyId = appKey(f);
        var validTo = java.time.LocalDateTime.now(f.clock).plusDays(40);
        KeyVersionRef v1 = f.registry.addVersion(keyId,
                new NewVersion(V1, null, "UPLOADED", null, null, validTo, "ops-alice"));
        f.registry.markVerified(v1, "e", "ops-alice");
        f.registry.enable(v1, "ops-bob", null);
        assertThat(f.registry.find(APP_3, KeyPurpose.INBOUND_HMAC).orElseThrow().expiresAt()).isEqualTo(validTo);

        assertThat(f.lifecycle.runOnce().expiringSoon()).as("还有 40 天，不提醒").isZero();
        f.clock.advance(Duration.ofDays(15));
        assertThat(f.lifecycle.runOnce().expiringSoon()).isEqualTo(1);
        assertThat(f.lifecycle.runOnce().expiringSoon()).as("同一版本只提醒一次").isZero();

        f.clock.advance(Duration.ofDays(30));
        assertThat(f.lifecycle.runOnce().expired()).isEqualTo(1);
        SecKey key = f.store.mapper(SecretMappers.KeyMapper.class).selectById(keyId);
        assertThat(key.getStatus()).isEqualTo("EXPIRED");
        assertThatThrownBy(() -> f.registry.current(keyId)).isInstanceOfSatisfying(SecretException.class,
                e -> assertThat(e.reason()).isEqualTo(SecretException.Reason.KEY_UNUSABLE));
        assertThat(verifiable(f, keyId)).isEmpty();
    }

    @Test
    @DisplayName("★★ 平台生成的密钥明文只在发放时给一次，并记一条 REVEAL_ON_ISSUE；之后只剩指纹")
    void generatedSecretRevealedOnceAndLogged() {
        SecretFixture f = SecretFixture.withPayKey();
        long keyId = appKey(f);

        var issued = f.registry.generate(keyId, 32, "ops-alice", "partner 3 onboarding");

        assertThat(issued.plaintext()).hasSizeGreaterThanOrEqualTo(32);
        assertThat(issued.toString()).doesNotContain(new String(issued.plaintext(), StandardCharsets.UTF_8));
        assertThat(f.registry.fingerprintOf(issued.ref())).isEqualTo(issued.fingerprint()).hasSize(32);
        List<SecAccessLog> logs = f.store.mapper(SecretMappers.AccessLogMapper.class).selectList(null);
        assertThat(logs).singleElement().satisfies(l -> {
            assertThat(l.getAccessType()).isEqualTo("REVEAL_ON_ISSUE");
            assertThat(l.getSecKeyId()).isEqualTo(keyId);
            assertThat(l.getSecSecretBlobId()).isEqualTo(versionRow(f, issued.ref()).getSecSecretBlobId());
            assertThat(l.getAccessorNo()).isEqualTo("ops-alice");
        });
        assertThat(versionRow(f, issued.ref()).getSource()).isEqualTo("GENERATED");
    }

    @Test
    @DisplayName("★ 公钥版本明文存 PEM、不建密文；指纹是 PEM 的 SHA-256")
    void publicMaterialStoredInClear() {
        SecretFixture f = SecretFixture.withPayKey();
        long keyId = f.registry.register(new NewKey(new KeyOwner(SecretNamespace.PAY, "CHANNEL_PLATFORM", 1),
                KeyPurpose.COUNTERPARTY_VERIFY, "wechat platform", "WECHAT", "RSA_SHA256", "CERTIFICATE", "ops"));
        String pem = "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----";
        KeyVersionRef v = f.registry.addVersion(keyId, new NewVersion(null, pem, "FETCHED", "SERIAL1", null, null, "SYSTEM"));

        SecKeyVersion row = versionRow(f, v);
        assertThat(row.getSecSecretBlobId()).isNull();
        assertThat(row.getPublicMaterial()).isEqualTo(pem);
        assertThat(row.getFingerprint()).hasSize(64);
        assertThat(f.store.mapper(SecretMappers.SecretBlobMapper.class).selectList(null)).isEmpty();
    }

    static SecKeyVersion versionRow(SecretFixture f, KeyVersionRef ref) {
        return f.store.mapper(SecretMappers.KeyVersionMapper.class).selectOne(Wrappers.<SecKeyVersion>lambdaQuery()
                .eq(SecKeyVersion::getSecKeyId, ref.keyId()).eq(SecKeyVersion::getVersionIndex, ref.versionIndex()));
    }

    static List<String> eventTypes(SecretFixture f, long keyId) {
        return f.store.mapper(SecretMappers.KeyEventMapper.class).selectList(Wrappers.<SecKeyEvent>lambdaQuery()
                        .eq(SecKeyEvent::getSecKeyId, keyId).orderByAsc(SecKeyEvent::getId))
                .stream().map(SecKeyEvent::getEventType).toList();
    }

    /** 可验签版本的材料（ACTIVE 在前）—— 经 verificationVersions + materialOf，与签名运算拿到的是同一组版本 */
    private static java.util.List<byte[]> verifiable(SecretFixture f, long keyId) {
        return f.registry.verificationVersions(keyId).stream().map(f.registry::materialOf).toList();
    }
}
