package ai.neargo.shop.trade.service;

import ai.neargo.shop.spi.trade.PricingFeeFacts.Definition;
import ai.neargo.shop.spi.trade.PricingFeeFacts.Rule;
import ai.neargo.shop.spi.trade.PricingFeeFacts.Plan;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Snapshot;
import ai.neargo.shop.spi.trade.PricingFeeScopePort.AdjustmentAuthority;
import ai.neargo.shop.spi.trade.IndustryFulfillmentPort.Reference;
import java.util.List;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;

public interface PricingFeeRuleService {
    record SaveReq(long expectedVersion, Definition definition, String status, String reason) {}

    Rule save(Scope scope, String actor, String ruleNo, SaveReq request, String key);

    Rule get(Scope scope, String ruleNo);

    Plan resolve(Snapshot source);
    void applyPlan(Scope scope, String actor, String orderNo, String subOrderNo, String quoteNo, Plan plan);
    void reconcileOnAmendment(Reference reference, String amendmentNo, String sourceHash);
    Reference originalReference(Scope scope, String applicationNo);
    List<AdjustmentAuthority> pendingAdjustments(Scope scope, String scopeNo);
    AdjustmentAuthority adjustment(Scope scope, String scopeNo, String applicationNo, String sourceHash);
    AdjustmentAuthority authorizeAdjustment(Scope scope, String applicationNo, long targetFeeMinor, String sourceHash);
    void completeAdjustment(Scope scope, String applicationNo, long targetFeeMinor, String sourceHash, String actor, String key);
}
