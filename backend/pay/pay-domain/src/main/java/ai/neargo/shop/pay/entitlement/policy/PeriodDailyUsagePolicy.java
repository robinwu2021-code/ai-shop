package ai.neargo.shop.pay.entitlement.policy;

/** Finite day quota never carries unused capacity to another day; callers persist the original bucket identity. */
public final class PeriodDailyUsagePolicy {
    public UsageQuota.Daily reserve(UsageQuota.Daily quota, int units) {
        return quota.reserve(units);
    }
    public UsageQuota.Daily commit(UsageQuota.Daily quota, int units) {
        return quota.commit(units);
    }
    public UsageQuota.Daily release(UsageQuota.Daily quota, int units) {
        return quota.release(units);
    }
}
