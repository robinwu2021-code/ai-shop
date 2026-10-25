package ai.neargo.shop.spi.trade;

import ai.neargo.shop.spi.trade.PricingFeeFacts.Plan;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Snapshot;

public interface PricingFeeRulePort {
    Plan resolve(Snapshot source);

    /**
     * Original quote evidence, same-primary order creation transaction; no network revalidation.
     */
    void applyPlan(
            PricingSourceFactsPort.Scope scope,
            String actor,
            String orderNo,
            String subOrderNo,
            String quoteNo,
            Plan plan);
}
