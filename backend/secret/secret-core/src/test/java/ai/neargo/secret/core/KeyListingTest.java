package ai.neargo.secret.core;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.secret.api.KeyOwner;
import ai.neargo.secret.api.KeyPurpose;
import ai.neargo.secret.api.KeyRegistry.KeySummary;
import ai.neargo.secret.api.KeyRegistry.NewKey;
import ai.neargo.secret.api.KeyRegistry.NewVersion;
import ai.neargo.secret.api.SecretNamespace;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 运营查看密钥（开发计划 M5b.3）：按命名空间列出，只有元数据；别的命名空间、别的环境不出现 */
class KeyListingTest {

    @Test
    @DisplayName("★★★ 列出本命名空间的密钥：归属、用途、名称、对端、当前版本；别的命名空间不出现；没有材料可拿")
    void listsMetadataOfOneNamespace() {
        SecretFixture f = SecretFixture.withPayKey();
        KeyOwner wechat = new KeyOwner(SecretNamespace.PAY, "CHANNEL_PLATFORM", 1);
        long keyId = f.registry.register(new NewKey(wechat, KeyPurpose.COUNTERPARTY_VERIFY, "微信平台证书", "WECHAT",
                "RSA_SHA256", "CERTIFICATE", "ops-alice"));
        f.registry.addVersion(keyId, new NewVersion(null, "-----BEGIN CERTIFICATE-----", "UPLOADED", "SN-1", null, null,
                "ops-alice"));
        f.registry.register(new NewKey(new KeyOwner(SecretNamespace.LOGISTICS, "PROVIDER", 1), KeyPurpose.OUTBOUND_SIGN,
                "快递100", "KUAIDI100", "HMAC_SHA256", "SYMMETRIC_SECRET", "ops-alice"));

        List<KeySummary> pay = f.registry.keys(SecretNamespace.PAY);

        assertThat(pay).singleElement().satisfies(k -> {
            assertThat(k.info().keyId()).isEqualTo(keyId);
            assertThat(k.info().owner()).isEqualTo(wechat);
            assertThat(k.info().purpose()).isEqualTo(KeyPurpose.COUNTERPARTY_VERIFY);
            assertThat(k.name()).isEqualTo("微信平台证书");
            assertThat(k.counterparty()).isEqualTo("WECHAT");
            assertThat(k.toString()).doesNotContain("BEGIN CERTIFICATE");
        });
        assertThat(f.registry.keys(SecretNamespace.LOGISTICS)).singleElement()
                .satisfies(k -> assertThat(k.name()).isEqualTo("快递100"));
    }
}
