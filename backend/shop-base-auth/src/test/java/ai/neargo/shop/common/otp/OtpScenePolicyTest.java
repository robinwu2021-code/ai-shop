package ai.neargo.shop.common.otp;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.otp.policy.*;
import ai.neargo.shop.common.otp.service.OtpSendService.Channel;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OtpScenePolicyTest {
    @Test void loginKeepsKeysTtlAndTrustedLegacyMode() {
        var policies = new OtpScenePolicies(new OtpSettings(), true);
        var rule = policies.require(OtpScenes.LOGIN, Channel.SMS, OtpEntries.BIZ_LOGIN, null);
        assertThat(rule.validity().get(Channel.SMS)).isEqualTo(Duration.ofMinutes(5));
        assertThat(rule.validity().get(Channel.EMAIL)).isEqualTo(Duration.ofMinutes(10));
        assertThat(policies.mode(rule, OtpEntries.BIZ_LOGIN)).isEqualTo(OtpScenePolicy.CaptchaMode.ADAPTIVE);
        assertThat(policies.mode(rule, OtpEntries.LEGACY_AUTH)).isEqualTo(OtpScenePolicy.CaptchaMode.RATE_ONLY);
        assertThat(OtpKeys.storageKey(OtpScenes.LOGIN, Channel.SMS, "13800000000", null)).isEqualTo("13800000000");
        assertThat(OtpKeys.storageKey(OtpScenes.LOGIN, Channel.EMAIL, "a@example.test", null))
                .isEqualTo("email:" + OtpKeys.hash("a@example.test"));
        assertThat(OtpKeys.captchaScope("proof", "OTP_SEND", "13800000000", OtpScenes.LOGIN, null))
                .isEqualTo(OtpKeys.hash("proof:OTP_SEND:13800000000"));
    }
    @Test void newScenesRequireExplicitEnablementAndCallerAndBusinessKey() {
        var settings = new OtpSettings();
        var policies = new OtpScenePolicies(settings, true);
        assertThatThrownBy(() -> policies.require(OtpScenes.PASSWORD_RESET, Channel.EMAIL, "MEMBER", null)).isInstanceOf(BizException.class);
        var pickup = new OtpSettings.Scene(); pickup.setEnabled(true); pickup.setEntries(Set.of("ORDER"));
        settings.getScenes().put(OtpScenes.ORDER_PICKUP_CONFIRM, pickup);
        var configured = new OtpScenePolicies(settings, true);
        assertThatThrownBy(() -> configured.require(OtpScenes.ORDER_PICKUP_CONFIRM, Channel.SMS, "ORDER", null)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> configured.require(OtpScenes.ORDER_PICKUP_CONFIRM, Channel.SMS, "UNKNOWN", "order1")).isInstanceOf(BizException.class);
        var rule = configured.require(OtpScenes.ORDER_PICKUP_CONFIRM, Channel.SMS, "ORDER", "order1");
        assertThat(rule.accountHourly()).isEqualTo(1);
        assertThat(rule.accountDaily()).isEqualTo(3);
        assertThatThrownBy(() -> configured.require("UNKNOWN", Channel.SMS, "ORDER", null)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> configured.require(OtpScenes.LOGIN, Channel.EMAIL, OtpEntries.BIZ_LOGIN, "order1")).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> configured.require(OtpScenes.ORDER_PICKUP_CONFIRM, Channel.EMAIL, "ORDER", "order1")).isInstanceOf(BizException.class);
    }
    @Test void keysSeparateSceneChannelRecipientAndBusiness() {
        String base = OtpKeys.storageKey(OtpScenes.ORDER_PICKUP_CONFIRM, Channel.SMS, "a", "order1");
        assertThat(Set.of(base,
                OtpKeys.storageKey(OtpScenes.PASSWORD_RESET, Channel.SMS, "a", "order1"),
                OtpKeys.storageKey(OtpScenes.ORDER_PICKUP_CONFIRM, Channel.EMAIL, "a", "order1"),
                OtpKeys.storageKey(OtpScenes.ORDER_PICKUP_CONFIRM, Channel.SMS, "b", "order1"),
                OtpKeys.storageKey(OtpScenes.ORDER_PICKUP_CONFIRM, Channel.SMS, "a", "order2"))).hasSize(5);
        assertThat(OtpKeys.captchaScope("proof", "OTP_SEND", "a", OtpScenes.PASSWORD_RESET, "order1"))
                .isNotEqualTo(OtpKeys.captchaScope("proof", "OTP_SEND", "a", OtpScenes.PASSWORD_RESET, "order2"));
    }
}
