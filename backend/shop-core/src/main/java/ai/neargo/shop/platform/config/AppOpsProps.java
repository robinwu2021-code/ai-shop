package ai.neargo.shop.platform.config;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AppOps installation tickets (nearboss online upgrades, plan B). Prefix {@code shop.appops}.
 *
 * <p>ai-shop is the app's business backend, so it holds the AppOps APP_BACKEND CLIENT credential and asks AppOps
 * (APP-API-13) for one-time enrollment tickets on the app's behalf. Only apps listed under {@code apps} get tickets,
 * each pinned to one AppOps profile and DIRECT_APK distribution. Empty {@code base-url} turns the endpoint off.
 *
 * <pre>
 * shop.appops.base-url: http://192.168.3.80:18090       # internal; never the public gateway
 * shop.appops.client-id / client-secret: (secret store)
 * shop.appops.apps.nearboss.profile-id / distribution-id
 * </pre>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "shop.appops")
public class AppOpsProps {

    private String baseUrl = "";

    private String clientId = "";

    private String clientSecret = "";

    /** app code (nearboss, hxmall) → AppOps profile and distribution. */
    private Map<String, Target> apps = new LinkedHashMap<>();

    /** Per client IP; tickets are cheap to ask for but each one is an AppOps write. */
    private int ticketsPerIpPerMinute = 10;

    /** Across all callers per app, so a spread-out flood still stays bounded. */
    private int ticketsPerAppPerMinute = 600;

    private int timeoutMs = 5000;

    public boolean enabled() {
        return !baseUrl.isBlank() && !clientId.isBlank() && !clientSecret.isBlank() && !apps.isEmpty();
    }

    @Getter
    @Setter
    public static class Target {
        private String profileId = "";
        private String distributionId = "";
    }
}
