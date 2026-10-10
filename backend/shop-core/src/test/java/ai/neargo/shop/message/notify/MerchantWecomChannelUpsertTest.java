package ai.neargo.shop.message.notify;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.message.entity.NotifyChannel;
import ai.neargo.shop.message.mapper.MessageMappers.NotifyChannelMapper;
import ai.neargo.shop.message.notify.impl.MerchantChannelServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.ObjectMapper;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 商家企微群那一行的保存（TDD-商家企微群来单通知 §2.1）。
 *
 * <p>两件事在**保存那一刻**就要拦下，不能留到来单那一刻才发现发不出去：
 * 凭证 JSON 里没有 {@code webhook} 字段、以及加密密钥没配。
 *
 * <p>后者尤其重要：缺钥时的正确行为是**拒绝**，而不是「先明文存着回头再加密」——
 * 那个「回头」永远不会来，而凭据已经明文在库里了。
 */
@DisplayName("商家企微群渠道的保存校验")
class MerchantWecomChannelUpsertTest {

    private static final String ENTITY = "MCH-1";
    private static final String GOOD = "{\"webhook\":\"https://qyapi.weixin.qq.com/x?key=a\"}";

    private MerchantChannelServiceImpl service(boolean credKeyConfigured) {
        MockEnvironment env = new MockEnvironment();
        NotifyChannelMapper mapper = mock(NotifyChannelMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);
        String key = credKeyConfigured
                ? Base64.getEncoder().encodeToString(new byte[32]) : "";
        return new MerchantChannelServiceImpl(mapper, new NotifyCredCipher(key),
                new PlatformChannelCredentials(env), new ObjectMapper());
    }

    @Test
    @DisplayName("★★★ 凭证 JSON 缺 webhook 字段 → 拒（配错在保存那刻就暴露）")
    void rejectsWebhookWithoutUrlField() {
        var s = service(true);
        assertThatThrownBy(() -> s.upsert(ENTITY, NotifyChannel.TYPE_WEBHOOK,
                NotifyChannel.PROV_WECOM, "{}", "{\"url\":\"https://x\"}", "ops"))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> s.upsert(ENTITY, NotifyChannel.TYPE_WEBHOOK,
                NotifyChannel.PROV_WECOM, "{}", "{\"webhook\":\"\"}", "ops"))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("★★★ 没配 SHOP_NOTIFY_CRED_KEY → 拒，**绝不明文落库**")
    void rejectsWhenCredKeyMissing() {
        var s = service(false);
        assertThatThrownBy(() -> s.upsert(ENTITY, NotifyChannel.TYPE_WEBHOOK,
                NotifyChannel.PROV_WECOM, "{}", GOOD, "ops"))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("字段齐、密钥在 → 存下来是密文，而且那一行是 MERCHANT/WEBHOOK/WECOM")
    void storesCipherNotPlaintext() {
        var ch = service(true).upsert(ENTITY, NotifyChannel.TYPE_WEBHOOK,
                NotifyChannel.PROV_WECOM, "{}", GOOD, "ops");

        assertThat(ch.getScope()).isEqualTo(NotifyChannel.SCOPE_MERCHANT);
        assertThat(ch.getOwnerNo()).isEqualTo(ENTITY);
        assertThat(ch.getChannelType()).isEqualTo(NotifyChannel.TYPE_WEBHOOK);
        assertThat(ch.getProvider()).isEqualTo(NotifyChannel.PROV_WECOM);
        // 密文里不该出现 URL 的任何一段 —— 这条就是「明文永不落库」的量具
        assertThat(ch.getSecretCipher()).isNotBlank()
                .doesNotContain("qyapi").doesNotContain("webhook").doesNotContain("key=a");
    }

    @Test
    @DisplayName("这条规格不让群机器人显示成「通道没配」或「走桩」—— 它天生只有商家那一种形态")
    void specDoesNotMisreportPlatformReadiness() {
        var creds = new PlatformChannelCredentials(new MockEnvironment());
        assertThat(creds.credsReady(NotifyChannel.TYPE_WEBHOOK, NotifyChannel.PROV_WECOM)).isTrue();
        assertThat(creds.isStub(NotifyChannel.TYPE_WEBHOOK, NotifyChannel.PROV_WECOM)).isFalse();
        assertThat(creds.missing(NotifyChannel.TYPE_WEBHOOK, NotifyChannel.PROV_WECOM)).isEmpty();
        assertThat(creds.requiredSecretKeys(NotifyChannel.TYPE_WEBHOOK, NotifyChannel.PROV_WECOM))
                .containsExactly("webhook");
    }
}
