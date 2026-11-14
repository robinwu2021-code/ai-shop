package ai.neargo.commerce.api.catalog;

/**
 * Digital periodDays is UTC days per unitCount cycle; hardware has null periodDays and unitCount
 * pieces. Gifts require allocationWeight=0. Component numbers are immutable within a version.
 */
/**
 * Null version/component identity requests server generation; non-null identifies an existing
 * draft/component. {@code title} is an optional display name (1..64 chars, null = none), frozen
 * with the SKU version like every other component field.
 */
public record SkuComponent(
        String componentNo,
        ai.neargo.commerce.api.common.ComponentKind kind,
        String planVersionNo,
        String entitlementCode,
        String hardwareSkuNo,
        Integer periodDays,
        int unitCount,
        long allocationWeight,
        boolean gift,
        String activationPolicy,
        String title) {
    /** Pre-title shape: no display name. */
    public SkuComponent(
            String componentNo,
            ai.neargo.commerce.api.common.ComponentKind kind,
            String planVersionNo,
            String entitlementCode,
            String hardwareSkuNo,
            Integer periodDays,
            int unitCount,
            long allocationWeight,
            boolean gift,
            String activationPolicy) {
        this(componentNo, kind, planVersionNo, entitlementCode, hardwareSkuNo, periodDays,
                unitCount, allocationWeight, gift, activationPolicy, null);
    }
}
