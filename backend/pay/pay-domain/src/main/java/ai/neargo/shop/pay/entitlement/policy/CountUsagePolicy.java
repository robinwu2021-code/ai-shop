package ai.neargo.shop.pay.entitlement.policy;

import ai.neargo.shop.pay.entitlement.policy.EntitlementValidityPolicy.EffectiveWindow;
import java.time.Instant;

/** New holds require a valid window; already committed source decisions may settle an old hold after expiry. */
public final class CountUsagePolicy {
    public UsageQuota.Count reserve(UsageQuota.Count quota, int units, EffectiveWindow window, Instant serviceAt) {
        if (quota == null || window == null || serviceAt == null) {
            throw new IllegalArgumentException("Quota, window and trusted service time are required");
        }
        if (!window.contains(serviceAt)) {
            throw new IllegalStateException("Entitlement is outside its effective window");
        }
        return quota.reserve(units);
    }

    /** Commit only previously allocated units; source decision and allocation checks belong to the use service. */
    public UsageQuota.Count commit(UsageQuota.Count quota, int units) {
        return quota.commit(units);
    }

    /** Expiration does not erase a previous hold or move its reserved units to a different operation. */
    public UsageQuota.Count release(UsageQuota.Count quota, int units) {
        return quota.release(units);
    }
}
