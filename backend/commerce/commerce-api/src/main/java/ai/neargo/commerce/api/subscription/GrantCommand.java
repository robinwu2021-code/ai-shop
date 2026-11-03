package ai.neargo.commerce.api.subscription;

import java.util.Optional;
import ai.neargo.commerce.api.common.*;

public record GrantCommand(StoreRef store, String operationNo, String entitlementCode, int durationDays, Optional<String> planVersionNo, String reason) {}
