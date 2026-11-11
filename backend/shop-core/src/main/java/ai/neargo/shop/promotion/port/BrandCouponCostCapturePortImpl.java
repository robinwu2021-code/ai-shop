package ai.neargo.shop.promotion.port;

import ai.neargo.shop.promotion.cost.BrandPromotionCostService;
import ai.neargo.shop.spi.entitlement.EntitlementUseFactsPort.UseFact;
import ai.neargo.shop.spi.marketing.BrandCouponCostCapturePort;
import org.springframework.stereotype.Component;

/** Keeps frozen cost capture with the promotion owner and joins the original order transaction. */
@Component
public final class BrandCouponCostCapturePortImpl implements BrandCouponCostCapturePort {
    private final BrandPromotionCostService costs;

    public BrandCouponCostCapturePortImpl(BrandPromotionCostService costs) {
        this.costs = costs;
    }

    @Override public void captureCoupon(String tenantNo, String operationNo, UseFact fact) {
        costs.captureCoupon(tenantNo, operationNo, fact);
    }
}
