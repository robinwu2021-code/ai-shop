package ai.neargo.commerce.api.catalog;

import java.util.Set;
import ai.neargo.commerce.api.common.*;

public record PlanDraft(String planNo, String typeCode, PlanTier tier, Set<String> entitlementCodes, int cycleDays, long expectedVersion) {}
