package ai.neargo.commerce.api.subscription;

import java.util.Optional;
import ai.neargo.commerce.api.common.*;

/** One row per source of value. Plan parents have a version and no standalone entitlementCode. */
public record EntitlementSource(String sourceNo, String entitlementCode, Optional<String> planVersionNo, String originNo, long durationMillis, long consumedMillis, Money valuationBasis, boolean permanent) {}
