package ai.neargo.shop.common.otp.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.otp.OtpEntries;
import ai.neargo.shop.common.otp.OtpScenes;
import ai.neargo.shop.common.otp.OtpScenePolicy;
import ai.neargo.shop.common.otp.OtpScenePolicy.CaptchaMode;
import ai.neargo.shop.common.otp.service.OtpSendService.Channel;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Startup snapshot, never queries config on the request path. */
public final class OtpScenePolicies {
    private final Map<String, OtpScenePolicy> policies;
    private final Map<String, Set<String>> entries;
    private final boolean captchaEnabled;
    public OtpScenePolicies(OtpSettings settings, boolean captchaEnabled) {
        this.captchaEnabled = captchaEnabled;
        if (!OtpScenes.ALL.containsAll(settings.getScenes().keySet()))
            throw new IllegalArgumentException("Unknown configured OTP scene");
        var rules = new HashMap<String, OtpScenePolicy>();
        var callers = new HashMap<String, Set<String>>();
        for (String scene : OtpScenes.ALL) {
            boolean login = OtpScenes.LOGIN.equals(scene);
            boolean pickup = OtpScenes.ORDER_PICKUP_CONFIRM.equals(scene);
            var override = settings.getScenes().getOrDefault(scene, new OtpSettings.Scene());
            Set<Channel> allowed = switch (scene) {
                case OtpScenes.PHONE_CHANGE, OtpScenes.ORDER_PICKUP_CONFIRM -> Set.of(Channel.SMS);
                case OtpScenes.EMAIL_CHANGE -> Set.of(Channel.EMAIL);
                default -> Set.of(Channel.SMS, Channel.EMAIL);
            };
            var mode = override.getCaptchaMode() != null ? override.getCaptchaMode()
                    : login || pickup ? CaptchaMode.ADAPTIVE : CaptchaMode.ALWAYS;
            var validity = Map.of(Channel.SMS, Duration.ofSeconds(override.getSmsTtlSeconds() == null ? 300 : override.getSmsTtlSeconds()),
                    Channel.EMAIL, Duration.ofSeconds(override.getEmailTtlSeconds() == null ? (login || OtpScenes.EMAIL_CHANGE.equals(scene) ? 600 : 300) : override.getEmailTtlSeconds()));
            rules.put(scene, new OtpScenePolicy(scene, override.getEnabled() == null ? login : override.getEnabled(),
                    override.getChannels() == null ? allowed : override.getChannels(), validity, mode,
                    override.getAccountHourly() == null ? (pickup ? 1 : settings.getAccountHourly()) : override.getAccountHourly(),
                    override.getAccountDaily() == null ? (pickup ? 3 : settings.getAccountDaily()) : override.getAccountDaily(), pickup));
            callers.put(scene, override.getEntries() == null ? (login ? Set.of(OtpEntries.BIZ_LOGIN, OtpEntries.LEGACY_AUTH) : Set.of())
                    : Set.copyOf(override.getEntries()));
        }
        policies = Map.copyOf(rules); entries = Map.copyOf(callers);
    }
    public OtpScenePolicy require(String scene, Channel channel, String entryCode, String businessKey) {
        var rule = policies.get(scene);
        if (rule == null || !rule.enabled() || !rule.channels().contains(channel)
                || !entries.get(scene).contains(entryCode)
                || (rule.businessKeyRequired() && (businessKey == null || businessKey.isBlank()))
                || (OtpScenes.LOGIN.equals(scene) && businessKey != null && !businessKey.isBlank()))
            throw BizException.of(ErrorCode.BAD_REQUEST);
        return rule;
    }
    public void requireChallenge(String scene, Channel channel, String businessKey) {
        var rule = policies.get(scene);
        if (rule == null || !rule.enabled() || !rule.channels().contains(channel)
                || (rule.businessKeyRequired() && (businessKey == null || businessKey.isBlank()))
                || (OtpScenes.LOGIN.equals(scene) && businessKey != null && !businessKey.isBlank()))
            throw BizException.of(ErrorCode.BAD_REQUEST);
    }
    public CaptchaMode mode(OtpScenePolicy rule, String entryCode) {
        if (!captchaEnabled || (OtpScenes.LOGIN.equals(rule.scene()) && OtpEntries.LEGACY_AUTH.equals(entryCode)))
            return CaptchaMode.RATE_ONLY;
        return rule.captchaMode();
    }
}
