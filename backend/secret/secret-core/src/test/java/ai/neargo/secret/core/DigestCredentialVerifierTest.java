package ai.neargo.secret.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.secret.api.CredentialVerifier.Credential;
import ai.neargo.secret.api.CredentialVerifier.Issued;
import ai.neargo.secret.api.CredentialVerifier.NewCredential;
import ai.neargo.secret.api.CredentialVerifier.Registration;
import ai.neargo.secret.api.CredentialVerifier.Verified;
import ai.neargo.secret.api.KeyOwner;
import ai.neargo.secret.api.KeyPurpose;
import ai.neargo.secret.api.KeyVersionRef;
import ai.neargo.secret.api.SecretException;
import ai.neargo.secret.api.SecretException.Reason;
import ai.neargo.secret.api.SecretNamespace;
import ai.neargo.secret.api.StorageTier;
import ai.neargo.secret.store.entity.SecAccessLog;
import ai.neargo.secret.store.entity.SecKeyVersion;
import ai.neargo.secret.store.mapper.SecretMappers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** API Key 类凭据进密钥库只存摘要（Q15）：发放、自助登记、验证、吊销、列出 */
class DigestCredentialVerifierTest {

    private static final KeyOwner STAFF_7 = new KeyOwner(SecretNamespace.PLATFORM, "OPS_STAFF", 7);
    private static final KeyOwner STAFF_8 = new KeyOwner(SecretNamespace.PLATFORM, "OPS_STAFF", 8);

    private final SecretFixture f = new SecretFixture("SANDBOX").start();
    private final DigestCredentialVerifier verifier = f.credentials();

    private static NewCredential credential(KeyOwner owner, String no) {
        return new NewCredential(owner, no, "cli", "READ_ONLY", Duration.ofDays(7), "ops-alice");
    }

    @Test
    @DisplayName("★★★ 发放：明文只给一次、库里只有摘要；出示即验出所有者、凭据号与范围；记一次发放时展示")
    void issuesOnceAndStoresOnlyTheDigest() {
        Issued issued = verifier.issue(credential(STAFF_7, "k-1"), "oak_");

        assertThat(issued.secret()).matches("oak_[A-Za-z0-9_-]{43}");
        assertThat(issued.toString()).doesNotContain(issued.secret());
        SecKeyVersion row = f.store.mapper(SecretMappers.KeyVersionMapper.class).selectList(null).getFirst();
        assertThat(row.getStorageTier()).isEqualTo("DIGEST");
        assertThat(row.getMaterialDigest()).isEqualTo(EnvelopeCipher.sha256Hex(
                issued.secret().getBytes(StandardCharsets.UTF_8)));
        assertThat(row.getSecSecretBlobId()).isNull();
        assertThat(row.getActiveKey()).as("DIGEST 版本各自有效，不占 ACTIVE 名额").isNull();
        assertThat(f.store.mapper(SecretMappers.SecretBlobMapper.class).selectList(null)).isEmpty();
        assertThat(f.store.mapper(SecretMappers.AccessLogMapper.class).selectList(null))
                .extracting(SecAccessLog::getAccessType).containsExactly("REVEAL_ON_ISSUE");

        assertThat(verifier.verify(SecretNamespace.PLATFORM, issued.secret()))
                .contains(new Verified(STAFF_7, "k-1", "READ_ONLY"));
        assertThat(verifier.verify(SecretNamespace.INVENTORY, issued.secret())).as("别的命名空间").isEmpty();
        assertThat(verifier.verify(SecretNamespace.PLATFORM, issued.secret() + "x")).isEmpty();
    }

    @Test
    @DisplayName("★★★ 材料拿不出来：materialOf 对 DIGEST 档拒绝，档位如实报告")
    void digestVersionsHaveNoMaterial() {
        verifier.issue(credential(STAFF_7, "k-1"), "oak_");
        long keyId = f.registry.find(STAFF_7, KeyPurpose.INBOUND_API_KEY).orElseThrow().keyId();
        KeyVersionRef ref = new KeyVersionRef(keyId, 1);

        assertThatThrownBy(() -> f.registry.materialOf(ref)).isInstanceOf(SecretException.class)
                .satisfies(e -> assertThat(((SecretException) e).reason()).isEqualTo(Reason.WRONG_USAGE));
        assertThat(f.keyVault.tierOf(ref)).isEqualTo(StorageTier.DIGEST);
    }

    @Test
    @DisplayName("★★★ 同一所有者可同时持有多张；过期、吊销后验不过；吊销幂等；revokeAll 只动本人")
    void expiryAndRevocation() {
        String a = verifier.issue(credential(STAFF_7, "k-1"), "oak_").secret();
        String b = verifier.issue(credential(STAFF_7, "k-2"), "oak_").secret();
        String c = verifier.issue(credential(STAFF_8, "k-3"), "oak_").secret();
        assertThat(verifier.verify(SecretNamespace.PLATFORM, a)).isPresent();
        assertThat(verifier.verify(SecretNamespace.PLATFORM, b)).isPresent();

        assertThat(verifier.revoke(STAFF_7, "k-1", "ops-bob", "leaked")).isTrue();
        assertThat(verifier.revoke(STAFF_7, "k-1", "ops-bob", "leaked")).isFalse();
        assertThat(verifier.revoke(STAFF_8, "k-2", "ops-bob", "not his")).as("不是本人的凭据").isFalse();
        assertThat(verifier.verify(SecretNamespace.PLATFORM, a)).isEmpty();
        assertThat(verifier.verify(SecretNamespace.PLATFORM, b)).isPresent();

        assertThat(verifier.revokeAll(STAFF_7, "SYSTEM", "staff disabled")).isEqualTo(1);
        assertThat(verifier.verify(SecretNamespace.PLATFORM, b)).isEmpty();
        assertThat(verifier.verify(SecretNamespace.PLATFORM, c)).isPresent();

        f.clock.advance(Duration.ofDays(7));
        assertThat(verifier.verify(SecretNamespace.PLATFORM, c)).as("到期即失效").isEmpty();
    }

    @Test
    @DisplayName("★★ 列出：只本人、新的在前、含吊销时间，没有任何材料或摘要")
    void listsMetadataNewestFirst() {
        verifier.issue(credential(STAFF_7, "k-1"), "oak_");
        f.clock.advance(Duration.ofMinutes(1));
        String second = verifier.issue(credential(STAFF_7, "k-2"), "oak_").secret();
        verifier.issue(credential(STAFF_8, "k-3"), "oak_");
        verifier.revoke(STAFF_7, "k-1", "ops-bob", null);

        List<Credential> listed = verifier.list(STAFF_7);

        assertThat(listed).extracting(Credential::credentialNo).containsExactly("k-2", "k-1");
        Credential newest = listed.getFirst();
        assertThat(newest.label()).isEqualTo("cli");
        assertThat(newest.scope()).isEqualTo("READ_ONLY");
        assertThat(newest.issuedBy()).isEqualTo("ops-alice");
        assertThat(Duration.between(newest.issuedAt(), newest.expiresAt())).isEqualTo(Duration.ofDays(7));
        assertThat(newest.revokedAt()).isNull();
        assertThat(listed.get(1).revokedAt()).isNotNull();
        assertThat(listed.toString()).doesNotContain(second);
        assertThat(verifier.list(new KeyOwner(SecretNamespace.PLATFORM, "OPS_STAFF", 99))).isEmpty();
    }

    @Test
    @DisplayName("★★★ 自助登记只给摘要：重复登记同样内容幂等（含已吊销），内容不同、凭据号被别人用、摘要已占用都冲突")
    void registersByDigestIdempotently() {
        String local = "oak_" + "L".repeat(43);
        String digest = EnvelopeCipher.sha256Hex(local.getBytes(StandardCharsets.UTF_8));

        Registration first = verifier.register(credential(STAFF_7, "k-1"), digest);
        assertThat(first.created()).isTrue();
        assertThat(verifier.verify(SecretNamespace.PLATFORM, local)).contains(new Verified(STAFF_7, "k-1", "READ_ONLY"));
        assertThat(verifier.register(credential(STAFF_7, "k-1"), digest))
                .isEqualTo(new Registration(first.credential(), false));
        assertThat(f.store.mapper(SecretMappers.AccessLogMapper.class).selectList(null)).as("没有明文经手").isEmpty();

        assertDuplicate(() -> verifier.register(new NewCredential(STAFF_7, "k-1", "other", "READ_ONLY",
                Duration.ofDays(7), "ops-alice"), digest));
        assertDuplicate(() -> verifier.register(credential(STAFF_8, "k-1"), "b".repeat(64)));
        assertDuplicate(() -> verifier.register(credential(STAFF_7, "k-2"), digest));
        assertDuplicate(() -> verifier.issue(credential(STAFF_8, "k-1"), "oak_"));

        verifier.revoke(STAFF_7, "k-1", "ops-alice", null);
        assertThat(verifier.register(credential(STAFF_7, "k-1"), digest).credential().revokedAt()).isNotNull();
        assertThat(verifier.verify(SecretNamespace.PLATFORM, local)).isEmpty();
    }

    @Test
    @DisplayName("★★ 环境隔离：沙箱发的凭据，生产进程验不过")
    void otherEnvironmentCannotVerify() {
        String secret = verifier.issue(credential(STAFF_7, "k-1"), "oak_").secret();

        SecretFixture production = f.restartAs("PRODUCTION").start();

        assertThat(production.credentials().verify(SecretNamespace.PLATFORM, secret)).isEmpty();
    }

    @Test
    @DisplayName("★★ 未配 shop.secret.env：读的一面当作没有凭据（停用账号时吊销全部不报错），发放仍拒绝")
    void unconfiguredEnvironmentReadsAsNoCredentials() {
        String secret = verifier.issue(credential(STAFF_7, "k-1"), "oak_").secret();

        DigestCredentialVerifier unconfigured = f.restartAs(null).start().credentials();

        assertThat(unconfigured.revokeAll(STAFF_7, "ops-alice", "账号停用")).isZero();
        assertThat(unconfigured.list(STAFF_7)).isEmpty();
        assertThat(unconfigured.verify(SecretNamespace.PLATFORM, secret)).isEmpty();
        assertThatThrownBy(() -> unconfigured.issue(credential(STAFF_7, "k-2"), "oak_"))
                .isInstanceOf(SecretException.class)
                .satisfies(e -> assertThat(((SecretException) e).reason()).isEqualTo(Reason.ENV_NOT_CONFIGURED));
    }

    private static void assertDuplicate(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOf(SecretException.class)
                .satisfies(e -> assertThat(((SecretException) e).reason()).isEqualTo(Reason.DUPLICATE));
    }
}
