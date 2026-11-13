package ai.neargo.shop.common.otp;

import ai.neargo.shop.common.otp.service.OtpSendService.Channel;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

public record OtpScenePolicy(String scene, boolean enabled, Set<Channel> channels,
                             Map<Channel, Duration> validity, CaptchaMode captchaMode,
                             int accountHourly, int accountDaily, boolean businessKeyRequired) {
    public enum CaptchaMode { ADAPTIVE, ALWAYS, RATE_ONLY }
    public OtpScenePolicy {
        channels = Set.copyOf(channels);
        validity = Map.copyOf(validity);
    }
}
