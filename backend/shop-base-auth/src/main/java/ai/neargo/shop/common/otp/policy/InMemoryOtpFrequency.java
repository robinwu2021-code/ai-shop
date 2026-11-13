package ai.neargo.shop.common.otp.policy;

import ai.neargo.shop.common.otp.OtpKeys;
import ai.neargo.shop.common.otp.OtpScenePolicy;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

/** Approximate fixed windows: bounded per account, constant platform state, no per-send timestamp queue. */
public final class InMemoryOtpFrequency {
    private final Map<String, Windows> accounts = new HashMap<>();
    private final Windows platform = new Windows();
    private final Clock clock;
    private final int maximumKeys, platformHourly, platformDaily;
    public InMemoryOtpFrequency(Clock clock, int maximumKeys, int platformHourly, int platformDaily) {
        this.clock = clock; this.maximumKeys = maximumKeys;
        this.platformHourly = platformHourly; this.platformDaily = platformDaily;
    }
    public synchronized boolean requiresCaptcha(String account, OtpScenePolicy policy) {
        long now = clock.millis();
        platform.expire(now);
        String key = OtpKeys.hash(account);
        Windows own = accounts.get(key);
        if (own != null) own.expire(now);
        if (own == null && accounts.size() >= maximumKeys) {
            accounts.entrySet().removeIf(e -> e.getValue().dayExpiry <= now);
            if (accounts.size() >= maximumKeys) return true;
        }
        return (own != null && (own.hour >= policy.accountHourly() || own.day >= policy.accountDaily()))
                || platform.hour >= platformHourly || platform.day >= platformDaily;
    }
    public synchronized void record(String account) {
        long now = clock.millis();
        platform.add(now);
        String key = OtpKeys.hash(account);
        Windows own = accounts.get(key);
        if (own == null && accounts.size() < maximumKeys) {
            own = new Windows(); accounts.put(key, own);
        }
        if (own != null) own.add(now);
    }
    private static final class Windows {
        long hour, day, hourExpiry, dayExpiry;
        void expire(long now) {
            if (now >= hourExpiry) hour = 0;
            if (now >= dayExpiry) day = 0;
        }
        void add(long now) {
            if (now >= hourExpiry) { hour = 0; hourExpiry = now + 3_600_000; }
            if (now >= dayExpiry) { day = 0; dayExpiry = now + 86_400_000; }
            hour++; day++;
        }
    }
}
