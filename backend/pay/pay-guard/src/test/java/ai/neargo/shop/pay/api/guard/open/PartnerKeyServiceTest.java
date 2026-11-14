package ai.neargo.shop.pay.api.guard.open;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.neargo.secret.api.KeyOwner;
import ai.neargo.secret.api.KeyPurpose;
import ai.neargo.secret.api.KeyRegistry;
import ai.neargo.secret.api.KeyRegistry.Generation;
import ai.neargo.secret.api.KeyRegistry.IssuedSecret;
import ai.neargo.secret.api.KeyRegistry.NewKey;
import ai.neargo.secret.api.KeyVersionRef;
import ai.neargo.secret.api.SecretFormat;
import ai.neargo.secret.api.SecretNamespace;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.pay.mapper.openapp.OpenAppMappers.PayAppMapper;
import ai.neargo.shop.pay.openapp.entity.PayApp;
import ai.neargo.shop.pay.api.guard.open.service.PartnerKeyService;
import ai.neargo.shop.pay.api.guard.open.service.PartnerKeyService.IssuedCredential;
import ai.neargo.shop.pay.api.guard.open.service.impl.PartnerKeyServiceImpl;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/**
 * 平台生成接入方的 AppSecret 与 EncryptKey，存进密钥模块（TDD-支付服务-开放API加解密与密钥分发 §2.1 / §2.3，
 * 数据库设计 §2）：材料只有 32 位，前缀按环境、只在返回给接入方的那一次拼上。
 */
class PartnerKeyServiceTest {

    private static final String MATERIAL = "Zk3Qm9Lp2Xv7Rt5Wb8Nc4Hd6Jf1Gs0Ay";

    private final PayAppMapper apps = mock(PayAppMapper.class);
    private final KeyRegistry registry = mock(KeyRegistry.class);
    private final PartnerKeyService service = new PartnerKeyServiceImpl(apps, providerOf(registry));

    private static ObjectProvider<KeyRegistry> providerOf(KeyRegistry registry) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        if (registry != null) {
            beans.registerSingleton("keyRegistry", registry);
        }
        return beans.getBeanProvider(KeyRegistry.class);
    }

    private PayApp app(long id, String ownerType, boolean sandbox) {
        PayApp a = new PayApp();
        a.setId(id);
        a.setAppNo("APP" + id);
        a.setOwnerType(ownerType);
        a.setStatus("ACTIVE");
        a.setSandbox(sandbox);
        when(apps.selectOne(any())).thenReturn(a);
        return a;
    }

    private void registryIssues(String serial) {
        when(registry.register(any())).thenReturn(41L);
        when(registry.generate(eq(41L), any(Generation.class))).thenReturn(new IssuedSecret(new KeyVersionRef(41L, 2),
                MATERIAL.getBytes(StandardCharsets.US_ASCII), "3f9a1c2e", serial));
    }

    @Test
    @DisplayName("★★★ 沙箱 AppSecret：登记 PARTNER_APP/pay_app.id 的 INBOUND HMAC，按 ALNUM_32 + sk_test_ + S 生成，只拼一次前缀")
    void sandboxAppSecret() {
        app(7, "PARTNER", true);
        registryIssues("S2026102702");

        IssuedCredential c = service.issueAppSecret("APP7", "dev-alice");

        ArgumentCaptor<NewKey> key = ArgumentCaptor.forClass(NewKey.class);
        verify(registry).register(key.capture());
        assertThat(key.getValue().owner()).isEqualTo(new KeyOwner(SecretNamespace.PAY, "PARTNER_APP", 7));
        assertThat(key.getValue().purpose()).isEqualTo(KeyPurpose.INBOUND_HMAC);
        assertThat(key.getValue().algorithm()).isEqualTo("HMAC_SHA256");
        assertThat(key.getValue().materialType()).isEqualTo("SYMMETRIC_SECRET");

        ArgumentCaptor<Generation> gen = ArgumentCaptor.forClass(Generation.class);
        verify(registry).generate(eq(41L), gen.capture());
        assertThat(gen.getValue().format()).isEqualTo(SecretFormat.ALNUM_32);
        assertThat(gen.getValue().displayPrefix()).isEqualTo("sk_test_");
        assertThat(gen.getValue().serialPrefix()).isEqualTo("S");
        assertThat(gen.getValue().issuedBy()).isEqualTo("dev-alice");

        assertThat(c.secret()).isEqualTo("sk_test_" + MATERIAL);
        assertThat(c.keySerial()).isEqualTo("S2026102702");
        assertThat(c.version()).isEqualTo(2);
        assertThat(c.fingerprint()).isEqualTo("sk_test_…3f9a1c2e");
        assertThat(c.toString()).doesNotContain(MATERIAL);
    }

    @Test
    @DisplayName("★★ 生产 AppSecret 用 sk_live_")
    void productionAppSecret() {
        app(8, "PARTNER", false);
        registryIssues("S2026102701");

        assertThat(service.issueAppSecret("APP8", "dev-bob").secret()).isEqualTo("sk_live_" + MATERIAL);
    }

    @Test
    @DisplayName("★★★ EncryptKey：PARTNER_DATA（INBOUND ENCRYPT）、AES_256_GCM，不加前缀，序列号前缀 E")
    void encryptKey() {
        app(7, "PARTNER", true);
        registryIssues("E2026102701");

        IssuedCredential c = service.issueEncryptKey("APP7", "dev-alice");

        ArgumentCaptor<NewKey> key = ArgumentCaptor.forClass(NewKey.class);
        verify(registry).register(key.capture());
        assertThat(key.getValue().purpose()).isEqualTo(KeyPurpose.PARTNER_DATA);
        assertThat(key.getValue().algorithm()).isEqualTo("AES_256_GCM");
        ArgumentCaptor<Generation> gen = ArgumentCaptor.forClass(Generation.class);
        verify(registry).generate(anyLong(), gen.capture());
        assertThat(gen.getValue().displayPrefix()).isNull();
        assertThat(gen.getValue().serialPrefix()).isEqualTo("E");
        assertThat(c.secret()).isEqualTo(MATERIAL);
        assertThat(c.fingerprint()).isEqualTo("…3f9a1c2e");
    }

    @Test
    @DisplayName("★★ 内部系统、停用的、不存在的接入方一律拒绝，密钥模块不被调用")
    void onlyActivePartnersGetKeys() {
        app(1, "INTERNAL", false);
        assertThatThrownBy(() -> service.issueAppSecret("APP1", "ops")).isInstanceOf(BizException.class);

        app(9, "PARTNER", false).setStatus("SUSPENDED");
        assertThatThrownBy(() -> service.issueEncryptKey("APP9", "ops")).isInstanceOf(BizException.class);

        when(apps.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.issueAppSecret("NOPE", "ops")).isInstanceOf(BizException.class);

        verify(registry, never()).register(any());
        verify(registry, never()).generate(anyLong(), any(Generation.class));
    }

    @Test
    @DisplayName("★ 没装密钥模块的进程（独立形态 pay-svc）：能启动，但发放时失败关闭，不静默返回空")
    void failsClosedWithoutSecretModule() {
        app(7, "PARTNER", true);
        PartnerKeyService bare = new PartnerKeyServiceImpl(apps, providerOf(null));

        assertThatThrownBy(() -> bare.issueAppSecret("APP7", "dev-alice"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("KeyRegistry");
    }
}
