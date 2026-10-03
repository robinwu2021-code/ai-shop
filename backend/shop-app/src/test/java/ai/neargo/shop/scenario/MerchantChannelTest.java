package ai.neargo.shop.scenario;

import ai.neargo.shop.message.entity.NotifyChannel;
import ai.neargo.shop.message.notify.MerchantChannelService;
import ai.neargo.shop.message.notify.NotifyChannelRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 外部接入：商家自带渠道（设计：触达推送中台 · N5）。
 *
 * <p>三条不变量：密钥**加密**落库（库里不是明文）、解密**能取回**原文（发送侧用）、
 * 配好密钥后状态派生成 READY。
 */
@SpringBootTest
@ActiveProfiles("test")
class MerchantChannelTest {

    @Autowired
    private MerchantChannelService svc;
    @Autowired
    private NotifyChannelRegistry registry;

    private static final String OWNER = "E-NCH-TEST";
    // GETUI 商家凭证：必须含 appId/appKey/masterSecret（与平台侧同一份规格）
    private static final String SECRET =
            "{\"appId\":\"pa\",\"appKey\":\"mk\",\"masterSecret\":\"top-secret-value\"}";

    @Test
    @DisplayName("★★★ 商家渠道：密钥加密落库、解密取回、状态 READY")
    void merchantChannelEncryptsAtRest() {
        NotifyChannel ch = svc.upsert(OWNER, NotifyChannel.TYPE_PUSH, NotifyChannel.PROV_GETUI,
                "{\"appId\":\"pub-123\"}", SECRET, "ST-OPS");

        // 落库的是密文，不是明文 —— 库被读走也拿不到商家密钥
        assertThat(ch.getSecretCipher()).as("必须存密文").isNotBlank();
        assertThat(ch.getSecretCipher()).as("密文里不能出现明文片段")
                .doesNotContain("top-secret-value").doesNotContain("masterSecret");
        assertThat(ch.getScope()).isEqualTo(NotifyChannel.SCOPE_MERCHANT);
        assertThat(ch.getOwnerNo()).isEqualTo(OWNER);

        // 发送侧解密能拿回原文
        assertThat(svc.decryptSecret(ch)).isEqualTo(SECRET);

        // 配好密钥 → 状态就绪（商家渠道看自己的密文，不看平台 env、不走桩）
        assertThat(registry.statusOf(ch)).isEqualTo(NotifyChannel.STATUS_READY);
    }

    @Test
    @DisplayName("★★ 幂等：同商家同类型同供应商只一条；改非密项不动已存密钥")
    void upsertIsIdempotentAndKeepsSecret() {
        String aliSecret = "{\"accessKeyId\":\"AAA\",\"accessKeySecret\":\"BBB\",\"sign\":\"数智邻购\"}";
        svc.upsert(OWNER, NotifyChannel.TYPE_SMS, NotifyChannel.PROV_ALI,
                "{\"sign\":\"旧签名\"}", aliSecret, "ST-OPS");
        // 第二次只改非密的配置，secret 传空 —— 不能把已存的密钥清掉
        NotifyChannel again = svc.upsert(OWNER, NotifyChannel.TYPE_SMS, NotifyChannel.PROV_ALI,
                "{\"sign\":\"新签名\"}", null, "ST-OPS");

        assertThat(svc.listForOwner(OWNER))
                .filteredOn(c -> NotifyChannel.TYPE_SMS.equals(c.getChannelType()))
                .as("同商家同类型同供应商只一条").hasSize(1);
        assertThat(again.getConfigJson()).contains("新签名");
        assertThat(svc.decryptSecret(again)).as("空 secret 不动已存密钥").isEqualTo(aliSecret);
    }

    @Test
    @DisplayName("★★ 凭证缺字段：保存时就拒，不留到发送才炸")
    void incompleteSecretRejectedAtSave() {
        // 个推缺 masterSecret —— 与平台侧同一份规格判定，保存这一步就拦下
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                svc.upsert("E-BAD", NotifyChannel.TYPE_PUSH, NotifyChannel.PROV_GETUI,
                        "{}", "{\"appId\":\"pa\",\"appKey\":\"mk\"}", "ST-OPS"))
                .isInstanceOf(ai.neargo.shop.common.BizException.class);
    }
}
