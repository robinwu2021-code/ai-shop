package ai.neargo.shop.pay.entitlement.policy;

import ai.neargo.shop.pay.entitlement.policy.EntitlementValidityPolicy.EffectiveWindow;
import java.time.Instant;

/** Period-count cards reuse the same total-unit accounting and the version's immutable time window. */
public final class PeriodCountUsagePolicy {
    private final CountUsagePolicy counts = new CountUsagePolicy();

    public UsageQuota.Count reserve(UsageQuota.Count quota, int units, EffectiveWindow window, Instant serviceAt) {
        return counts.reserve(quota, units, window, serviceAt);
    }

    public UsageQuota.Count commit(UsageQuota.Count quota, int units) {
        return counts.commit(quota, units);
    }

    public UsageQuota.Count release(UsageQuota.Count quota, int units) {
        return counts.release(quota, units);
    }
}
