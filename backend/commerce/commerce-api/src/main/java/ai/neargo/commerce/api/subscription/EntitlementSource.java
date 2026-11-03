package ai.neargo.commerce.api.subscription;

import java.util.Optional;
import ai.neargo.commerce.api.common.*;

public record EntitlementSource(String sourceNo, String entitlementCode, Optional<String> planVersionNo, String originNo, long durationMillis, long consumedMillis, Money valuationBasis, boolean permanent) {}
