package ai.neargo.commerce.api.catalog;

import java.util.Optional;
import ai.neargo.commerce.api.common.*;

public record SkuComponent(String componentNo, ComponentKind kind, Optional<String> planVersionNo, Optional<String> entitlementCode, long quantity, String unit, boolean gift) {}
