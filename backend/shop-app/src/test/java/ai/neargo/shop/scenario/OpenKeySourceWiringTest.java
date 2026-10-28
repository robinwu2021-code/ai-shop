package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.pay.openapp.AppSignatureVerifier;
import ai.neargo.shop.pay.openapp.AppSigner;
import ai.neargo.shop.pay.secret.SecretAppSignatures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@code shop.pay.open.key-source=secret}（开发计划 M3.1 / M3.10）：验签与推送签名都换成经密钥模块的实现，
 * 进程能起来（{@code KeyVault} 与 {@code KeyRegistry} 同在 {@code SecretDataSourceConfig}），且只有这一个实现。
 */
@SpringBootTest(properties = {"shop.pay.open.key-source=secret",
        "spring.datasource.url=jdbc:h2:mem:pay-i3;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
class OpenKeySourceWiringTest {

    @Autowired private AppSignatureVerifier verifier;
    @Autowired private AppSigner signer;

    @Test
    @DisplayName("★★ key-source=secret：验签与出站签名都是 SecretAppSignatures，部署配置那一份不再装")
    void secretSourceIsTheOnlyImplementation() {
        assertThat(verifier).isInstanceOf(SecretAppSignatures.class);
        assertThat(signer).isSameAs(verifier);
    }
}
