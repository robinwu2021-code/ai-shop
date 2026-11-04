package ai.neargo.commerce.api.mall;

import ai.neargo.commerce.api.common.*;

/** Explicit SKU disambiguates published prices; legacy requests require a unique eligible SKU. */
public record UpgradeCommand(
        StoreRef store,
        String targetPlanVersionNo,
        String operationNo,
        long expectedEntitlementVersion,
        String targetSkuVersionNo) {
    public UpgradeCommand(
            StoreRef store,
            String targetPlanVersionNo,
            String operationNo,
            long expectedEntitlementVersion) {
        this(store, targetPlanVersionNo, operationNo, expectedEntitlementVersion, null);
    }
}
