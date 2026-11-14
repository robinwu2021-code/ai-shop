package ai.neargo.commerce.api.subscription;

import java.util.List;
import ai.neargo.commerce.api.common.*;

public record SubscriptionView(StoreRef store, long entitlementVersion, List<EntitlementSource> sources,
        List<AccessSegment> segments, String businessTypeCode, String currency, String currentPlanVersionNo, long serverNow) {
    public SubscriptionView(StoreRef store, long entitlementVersion, List<EntitlementSource> sources, List<AccessSegment> segments) {
        this(store, entitlementVersion, sources, segments, null, null, null, 0);
    }
}
