package ai.neargo.shop.pay.entitlement.dto;

/** Configuration excludes counters supplied by clients; all usage is derived from durable grant transitions. */
public final class IssuePolicyViews {
    private IssuePolicyViews() { }
    public record SavePolicy(long expectedRevision, Long limitCount, Long maxExposureMinor,
                             int perUserLimit, boolean enabled) { }
    public record PolicyView(String templateNo, Long limitCount, long heldCount, long issuedCount,
                             Long maxExposureMinor, long heldExposureMinor, long usedExposureMinor,
                             int perUserLimit, boolean enabled, long revision) { }
}
