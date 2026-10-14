package ai.neargo.shop.message.notify;

import ai.neargo.shop.message.entity.NotifyChannel;
import ai.neargo.shop.message.notify.NotifyChannelService.Credential;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 平台统一凭证真源（设计：触达推送中台 · 平台侧凭证管理）。
 *
 * <p>验四件事：缺配时判「没配」、配齐后判「就绪」、密钥值不经凭证清单外泄、
 * 可选供应商（FCM/APNs）的 required 标注不误报。
 */
@DisplayName("平台统一凭证真源")
class PlatformChannelCredentialsTest {

    @Test
    @DisplayName("★★★ 缺配 → 未就绪 + 走桩缺省；配齐 → 就绪 + 真发")
    void readinessFromEnv() {
        MockEnvironment env = new MockEnvironment();
        PlatformChannelCredentials creds = new PlatformChannelCredentials(env);

        // 什么都没配：个推未就绪、走桩（stub 缺省 true）
        assertThat(creds.credsReady(NotifyChannel.TYPE_PUSH, NotifyChannel.PROV_GETUI)).isFalse();
        assertThat(creds.isStub(NotifyChannel.TYPE_PUSH, NotifyChannel.PROV_GETUI)).isTrue();

        // 配齐个推三项 + 关桩：就绪、真发
        env.setProperty("shop.push.getui.app-id", "A");
        env.setProperty("shop.push.getui.app-key", "K");
        env.setProperty("shop.push.getui.master-secret", "S");
        env.setProperty("shop.push.stub", "false");
        assertThat(creds.credsReady(NotifyChannel.TYPE_PUSH, NotifyChannel.PROV_GETUI)).isTrue();
        assertThat(creds.isStub(NotifyChannel.TYPE_PUSH, NotifyChannel.PROV_GETUI)).isFalse();

        // 缺一项就不算齐
        env.setProperty("shop.push.getui.master-secret", "");
        assertThat(creds.credsReady(NotifyChannel.TYPE_PUSH, NotifyChannel.PROV_GETUI)).isFalse();
    }

    @Test
    @DisplayName("★★★ 凭证清单只回 present，密钥值不外泄")
    void credentialsNeverLeakSecretValues() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("shop.sms.ali.access-key-id", "AKID");
        env.setProperty("shop.sms.ali.access-key-secret", "TOP-SECRET-VALUE");
        PlatformChannelCredentials creds = new PlatformChannelCredentials(env);

        List<Credential> list = creds.credentials(NotifyChannel.TYPE_SMS, NotifyChannel.PROV_ALI);
        // 凭证项只有 envVar/present/required，没有值
        assertThat(list).allSatisfy(c ->
                assertThat(c.toString()).doesNotContain("TOP-SECRET-VALUE"));
        assertThat(list).anySatisfy(c -> {
            assertThat(c.envVar()).isEqualTo("ALI_SMS_SK");
            assertThat(c.present()).isTrue();
        });
        // value() 只放行非密标识；密钥属性也能读到值但调用方（health）只对非密项用它
        assertThat(creds.value("shop.sms.ali.access-key-id")).isEqualTo("AKID");
    }

    @Test
    @DisplayName("★★ 可选供应商（FCM/APNs）required=false，必需供应商 required=true")
    void optionalProvidersMarkedNotRequired() {
        PlatformChannelCredentials creds = new PlatformChannelCredentials(new MockEnvironment());
        assertThat(creds.credentials(NotifyChannel.TYPE_PUSH, NotifyChannel.PROV_GETUI))
                .as("个推是必需供应商").allSatisfy(c -> assertThat(c.required()).isTrue());
        assertThat(creds.credentials(NotifyChannel.TYPE_PUSH, NotifyChannel.PROV_FCM))
                .as("FCM 是可选供应商").allSatisfy(c -> assertThat(c.required()).isFalse());
        assertThat(creds.credentials(NotifyChannel.TYPE_SMS, NotifyChannel.PROV_ALI))
                .as("短信是必需通道").allSatisfy(c -> assertThat(c.required()).isTrue());
    }

    @Test
    @DisplayName("★★ 无凭证通道（INAPP）恒就绪、从不走桩")
    void inAppNeedsNoCredentials() {
        PlatformChannelCredentials creds = new PlatformChannelCredentials(new MockEnvironment());
        assertThat(creds.credsReady(NotifyChannel.TYPE_INAPP, NotifyChannel.PROV_INTERNAL)).isTrue();
        assertThat(creds.isStub(NotifyChannel.TYPE_INAPP, NotifyChannel.PROV_INTERNAL)).isFalse();
        assertThat(creds.credentials(NotifyChannel.TYPE_INAPP, NotifyChannel.PROV_INTERNAL)).isEmpty();
    }
}
