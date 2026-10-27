package ai.neargo.shop.pay.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.secret.api.KeyOwner;
import ai.neargo.secret.api.KeyPurpose;
import ai.neargo.secret.api.KeyRegistry;
import ai.neargo.secret.api.KeyVersionRef;
import ai.neargo.secret.api.SecretException;
import ai.neargo.secret.api.SecretNamespace;
import ai.neargo.shop.pay.mapper.openapp.OpenAppMappers.PayAppMapper;
import ai.neargo.shop.pay.openapp.entity.PayApp;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** AC9：接入方验签密钥从 sec_key 取 —— 按 pay_app.id 与归属类型找密钥，轮换期两把都返回 */
class SecretAppKeyResolverTest {

    private static PayAppMapper appsWith(PayApp app) {
        PayAppMapper apps = mock(PayAppMapper.class);
        when(apps.selectOne(any())).thenReturn(app);
        return apps;
    }

    private static PayApp app(long id, String ownerType) {
        PayApp a = new PayApp();
        a.setId(id);
        a.setAppNo("APP" + id);
        a.setOwnerType(ownerType);
        return a;
    }

    @Test
    @DisplayName("★★★ 第三方接入方：找 PARTNER_APP/pay_app.id 的 INBOUND HMAC 密钥，重叠期新旧两把都给")
    void partnerGetsBothVersionsDuringOverlap() {
        var registry = new StubRegistry(Map.of(
                new KeyOwner(SecretNamespace.PAY, "PARTNER_APP", 3), List.of("new-secret", "old-secret")));

        var resolver = new SecretAppKeyResolver(appsWith(app(3, "PARTNER")), registry);

        assertThat(resolver.hmacSecretsOf("APP3")).containsExactly("new-secret", "old-secret");
    }

    @Test
    @DisplayName("★★ 内部系统走 INTERNAL_APP；找不到接入方、找不到密钥、密钥不可用 → 空表（验签失败）")
    void internalAndMissingCases() {
        var registry = new StubRegistry(Map.of(
                new KeyOwner(SecretNamespace.PAY, "INTERNAL_APP", 1), List.of("mall-key")));

        assertThat(new SecretAppKeyResolver(appsWith(app(1, "INTERNAL")), registry).hmacSecretsOf("APP1"))
                .containsExactly("mall-key");
        assertThat(new SecretAppKeyResolver(appsWith(null), registry).hmacSecretsOf("NOPE")).isEmpty();
        assertThat(new SecretAppKeyResolver(appsWith(app(2, "PARTNER")), registry).hmacSecretsOf("APP2")).isEmpty();
        registry.broken = true;
        assertThat(new SecretAppKeyResolver(appsWith(app(1, "INTERNAL")), registry).hmacSecretsOf("APP1")).isEmpty();
    }

    /** 只实现解析器用到的两个方法；其余不该被调到 */
    private static final class StubRegistry implements KeyRegistry {

        private final Map<KeyOwner, List<String>> materials;
        boolean broken;

        StubRegistry(Map<KeyOwner, List<String>> materials) {
            this.materials = materials;
        }

        @Override
        public Optional<KeyInfo> find(KeyOwner owner, KeyPurpose purpose) {
            if (!KeyPurpose.INBOUND_HMAC.equals(purpose) || !materials.containsKey(owner)) {
                return Optional.empty();
            }
            return Optional.of(new KeyInfo(owner.ownerId() + 100, owner, purpose, "ACTIVE", 2, null));
        }

        @Override
        public List<byte[]> verificationMaterials(long keyId) {
            if (broken) {
                throw new SecretException(SecretException.Reason.KEY_UNUSABLE, "key " + keyId);
            }
            return materials.entrySet().stream().filter(e -> e.getKey().ownerId() + 100 == keyId).findFirst()
                    .map(e -> e.getValue().stream().map(s -> s.getBytes(StandardCharsets.UTF_8)).toList())
                    .orElse(List.of());
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
        public KeyVersionRef current(long keyId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public byte[] materialOf(KeyVersionRef ref) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String fingerprintOf(KeyVersionRef ref) {
            throw new UnsupportedOperationException();
        }

        @Override
        public IssuedSecret generate(long keyId, Generation generation) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<KeyVersionRef> verificationVersions(long keyId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<KeyVersionRef> findVersion(long keyId, String externalKeyId) {
            throw new UnsupportedOperationException();
        }
    }
}
