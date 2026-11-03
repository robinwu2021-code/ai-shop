package ai.neargo.commerce.api.catalog;

/**
 * Digital periodDays is UTC days per unitCount cycle; hardware has null periodDays and unitCount
 * pieces. Gifts require allocationWeight=0. Component numbers are immutable within a version.
 */
/**
 * Null version/component identity requests server generation; non-null identifies an existing
 * draft/component.
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
        String activationPolicy) {}
