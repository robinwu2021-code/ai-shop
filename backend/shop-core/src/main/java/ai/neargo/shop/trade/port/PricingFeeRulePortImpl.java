package ai.neargo.shop.trade.port;

import ai.neargo.shop.spi.trade.IndustryFulfillmentPort;
import ai.neargo.shop.spi.trade.PricingFeeFacts.Plan;
import ai.neargo.shop.spi.trade.PricingFeeRulePort;
import ai.neargo.shop.spi.trade.PricingFeeScopePort;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Snapshot;
import ai.neargo.shop.trade.service.PricingFeeRuleService;
import org.springframework.stereotype.Component;

import java.util.List;

/** 收费规则的跨域出口：只委托，事务与校验都在 {@link PricingFeeRuleService} 的实现里。 */
@Component
public class PricingFeeRulePortImpl implements PricingFeeRulePort, PricingFeeScopePort {
    private final PricingFeeRuleService rules;

    public PricingFeeRulePortImpl(PricingFeeRuleService rules) {
        this.rules = rules;
    }

    @Override
    public Plan resolve(Snapshot source) {
        return rules.resolve(source);
    }

    @Override
    public void applyPlan(Scope scope, String actor, String orderNo, String subOrderNo, String quoteNo, Plan plan) {
        rules.applyPlan(scope, actor, orderNo, subOrderNo, quoteNo, plan);
    }

    @Override
    public void reconcileOnAmendment(IndustryFulfillmentPort.Reference reference, String amendmentNo, String sourceHash) {
        rules.reconcileOnAmendment(reference, amendmentNo, sourceHash);
    }

    @Override
    public IndustryFulfillmentPort.Reference originalReference(Scope scope, String applicationNo) {
        return rules.originalReference(scope, applicationNo);
    }

    @Override
    public List<AdjustmentAuthority> pendingAdjustments(Scope scope, String scopeNo) {
        return rules.pendingAdjustments(scope, scopeNo);
    }

    @Override
    public AdjustmentAuthority adjustment(Scope scope, String scopeNo, String applicationNo, String sourceHash) {
        return rules.adjustment(scope, scopeNo, applicationNo, sourceHash);
    }

    @Override
    public AdjustmentAuthority authorizeAdjustment(
            Scope scope, String applicationNo, long targetFeeMinor, String sourceHash) {
        return rules.authorizeAdjustment(scope, applicationNo, targetFeeMinor, sourceHash);
    }

    @Override
    public void completeAdjustment(
            Scope scope, String applicationNo, long targetFeeMinor, String sourceHash, String actor, String key) {
        rules.completeAdjustment(scope, applicationNo, targetFeeMinor, sourceHash, actor, key);
    }
}
