package ai.neargo.shop.scenario;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.message.entity.NotifyChannel;
import ai.neargo.shop.message.notify.NotifyChannelRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 渠道注册表（设计：触达推送中台-模块抽象 · N2）。
 *
 * <p>验三件事：种子把现有通道登齐、状态**读时派生**得对（测试环境一律走桩）、
 * 软启停即时生效且 INAPP 关不掉。
 */
@SpringBootTest
@ActiveProfiles("test")
class NotifyChannelRegistryTest {

    @Autowired
    private NotifyChannelRegistry registry;

    @Test
    @DisplayName("★★ 种子登齐：现有各通道 PLATFORM 都在，外部通道各有 TEST(桩)")
    void seedCoversExistingChannels() {
        List<NotifyChannel> all = registry.list();
        // 平台侧七条（SMS/MAIL/WXSUB + PUSH×3 + INAPP）
        assertThat(all).filteredOn(c -> NotifyChannel.SCOPE_PLATFORM.equals(c.getScope()))
                .extracting(NotifyChannel::getChannelNo)
                .contains("NCH-SMS-ALI", "NCH-MAIL-SMTP", "NCH-WXSUB-WECHAT",
                        "NCH-PUSH-GETUI", "NCH-PUSH-FCM", "NCH-PUSH-APNS", "NCH-INAPP");
        // 四条外部通道各有测试接入
        assertThat(all).filteredOn(c -> NotifyChannel.SCOPE_TEST.equals(c.getScope()))
                .hasSizeGreaterThanOrEqualTo(4);
    }

    @Test
    @DisplayName("★★★ 状态读时派生：测试环境未配密钥 → 平台通道走桩(STUB)，INAPP 就绪(READY)")
    void statusDerivedFromEnvAndSwitch() {
        for (NotifyChannel ch : registry.list()) {
            // 只看种子登记的平台/测试渠道；商家渠道(MERCHANT)由 MerchantChannelTest 造，另有规则
            if (NotifyChannel.SCOPE_MERCHANT.equals(ch.getScope())) {
                continue;
            }
            String status = registry.statusOf(ch);
            if (NotifyChannel.SCOPE_TEST.equals(ch.getScope())) {
                assertThat(status).as("测试接入恒 STUB").isEqualTo(NotifyChannel.STATUS_STUB);
            } else if (NotifyChannel.TYPE_INAPP.equals(ch.getChannelType())) {
                // 站内信无需凭据、从不走桩 → 开着就是 READY
                assertThat(status).as("INAPP 平台通道就绪").isEqualTo(NotifyChannel.STATUS_READY);
            } else {
                // 其余平台通道在测试环境默认 shop.*.stub=true → STUB
                assertThat(status).as("%s 测试环境走桩", ch.getChannelNo())
                        .isEqualTo(NotifyChannel.STATUS_STUB);
            }
        }
    }

    @Test
    @DisplayName("★★★ 软启停即时生效：关掉即 DISABLED，开回即恢复派生")
    void softDisableFlipsStatus() {
        var ch = registry.setEnabled("NCH-PUSH-GETUI", false, "ST-TEST");
        assertThat(registry.statusOf(ch))
                .as("软关优先于一切派生：运营关了就是 DISABLED").isEqualTo(NotifyChannel.STATUS_DISABLED);

        var back = registry.setEnabled("NCH-PUSH-GETUI", true, "ST-TEST");
        assertThat(registry.statusOf(back)).isEqualTo(NotifyChannel.STATUS_STUB);
    }

    @Test
    @DisplayName("★★ INAPP 关不掉：站内信是事实记录，后端拒绝软关")
    void inAppCannotBeDisabled() {
        assertThatThrownBy(() -> registry.setEnabled("NCH-INAPP", false, "ST-TEST"))
                .as("站内信不可关，与场景×通道里 INAPP 不可关同理")
                .isInstanceOf(BizException.class);
    }
}
