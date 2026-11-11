package ai.neargo.shop.spi.marketing;

import ai.neargo.shop.spi.entitlement.EntitlementUseFactsPort.UseFact;

/** Captures owner-confirmed coupon allocations in the caller's existing Core transaction. */
public interface BrandCouponCostCapturePort {
    void captureCoupon(String tenantNo, String operationNo, UseFact fact);
}
