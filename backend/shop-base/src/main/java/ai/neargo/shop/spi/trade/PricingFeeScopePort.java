package ai.neargo.shop.spi.trade;

import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;

import java.util.List;

public interface PricingFeeScopePort {
    void reconcileOnAmendment(
            IndustryFulfillmentPort.Reference reference, String amendmentNo, String sourceHash);

    record AdjustmentAuthority(
            String applicationNo,
            String orderNo,
            String subOrderNo,
            String currency,
            long currentFeeMinor,
            long targetFeeMinor,
            String sourceHash) {}

    IndustryFulfillmentPort.Reference originalReference(Scope scope, String applicationNo);

    List<AdjustmentAuthority> pendingAdjustments(Scope scope, String scopeNo);

    AdjustmentAuthority adjustment(
            Scope scope, String scopeNo, String applicationNo, String sourceHash);

    AdjustmentAuthority authorizeAdjustment(
            Scope scope, String applicationNo, long targetFeeMinor, String sourceHash);

    /**
     * Joins the original amendment transaction. No arbitrary amount can become an adjustment
     * authority.
     */
    void completeAdjustment(
            Scope scope,
            String applicationNo,
            long targetFeeMinor,
            String sourceHash,
            String actor,
            String key);
}
