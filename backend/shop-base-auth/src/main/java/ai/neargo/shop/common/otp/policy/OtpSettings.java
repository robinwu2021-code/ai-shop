package ai.neargo.shop.common.otp.policy;

import ai.neargo.shop.common.otp.OtpScenePolicy.CaptchaMode;
import ai.neargo.shop.common.otp.service.OtpSendService.Channel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter @Setter @Validated
@ConfigurationProperties("shop.otp.adaptive")
public class OtpSettings {
    @Min(1) private int accountHourly = 3;
    @Min(1) private int accountDaily = 5;
    @Min(1) private int platformHourly = 500;
    @Min(1) private int platformDaily = 3000;
    @Min(1) private int maximumKeys = 100000;
    @Valid @NotNull private Map<String, Scene> scenes = new HashMap<>();
    @Getter @Setter
    public static class Scene {
        private Boolean enabled;
        private Set<Channel> channels;
        private CaptchaMode captchaMode;
        @Min(1) private Integer accountHourly;
        @Min(1) private Integer accountDaily;
        @Min(1) private Integer smsTtlSeconds;
        @Min(1) private Integer emailTtlSeconds;
        private Set<String> entries;
    }
}
