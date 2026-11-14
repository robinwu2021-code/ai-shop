package ai.neargo.commerce.api.catalog;

/**
 * Null version/component identity requests server generation; non-null identifies an existing
 * draft/component.
 */
public record PlanDraft(
        String planNo,
        String planVersionNo,
        String typeCode,
        ai.neargo.commerce.api.common.PlanTier tier,
        String displayName,
        String summary,
        java.util.List<String> entitlementCodes,
        String policyVersion,
        long expectedVersion,
        String reason) {
    public PlanDraft {
        entitlementCodes = java.util.List.copyOf(entitlementCodes);
    }
}
