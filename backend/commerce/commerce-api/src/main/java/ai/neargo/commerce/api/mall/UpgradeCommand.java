package ai.neargo.commerce.api.mall;

import ai.neargo.commerce.api.common.*;

public record UpgradeCommand(StoreRef store, String targetPlanVersionNo, String operationNo, long expectedEntitlementVersion) {}
