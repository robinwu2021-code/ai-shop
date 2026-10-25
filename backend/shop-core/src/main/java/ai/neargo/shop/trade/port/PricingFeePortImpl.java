package ai.neargo.shop.trade.port;

import ai.neargo.shop.spi.trade.IndustryFulfillmentPort.Reference;
import ai.neargo.shop.spi.trade.PricingFeeFacts.Plan;
import ai.neargo.shop.spi.trade.PricingFeeRulePort;
import ai.neargo.shop.spi.trade.PricingFeeScopePort;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Snapshot;
import ai.neargo.shop.trade.service.PricingFeeRuleService;
import java.util.List;
import org.springframework.stereotype.Component;

/** Cross-domain fee capabilities delegate to the original transactional owner. */
@Component
public class PricingFeePortImpl implements PricingFeeRulePort, PricingFeeScopePort {
    private final PricingFeeRuleService service;
    public PricingFeePortImpl(PricingFeeRuleService service) { this.service = service; }
    @Override public Plan resolve(Snapshot source) { return service.resolve(source); }
    @Override public void applyPlan(Scope scope, String actor, String order, String sub, String quote, Plan plan) {
        service.applyPlan(scope, actor, order, sub, quote, plan);
    }
    @Override public void reconcileOnAmendment(Reference ref, String amendment, String hash) {
        service.reconcileOnAmendment(ref, amendment, hash);
    }
    @Override public Reference originalReference(Scope scope, String application) {
        return service.originalReference(scope, application);
    }
    @Override public List<AdjustmentAuthority> pendingAdjustments(Scope scope, String no) {
        return service.pendingAdjustments(scope, no);
    }
    @Override public AdjustmentAuthority adjustment(Scope scope, String no, String application, String hash) {
        return service.adjustment(scope, no, application, hash);
    }
    @Override public AdjustmentAuthority authorizeAdjustment(Scope scope, String application, long target, String hash) {
        return service.authorizeAdjustment(scope, application, target, hash);
    }
    @Override public void completeAdjustment(Scope scope, String application, long target, String hash, String actor, String key) {
        service.completeAdjustment(scope, application, target, hash, actor, key);
    }
}
