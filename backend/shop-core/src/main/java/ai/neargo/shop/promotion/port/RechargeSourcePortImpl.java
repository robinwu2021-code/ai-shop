package ai.neargo.shop.promotion.port;

import ai.neargo.shop.promotion.service.RechargeIntentService;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.spi.storedvalue.RechargeSourcePort;
import org.springframework.stereotype.Component;

@Component
public class RechargeSourcePortImpl implements RechargeSourcePort {
    private final RechargeIntentService intents;
    public RechargeSourcePortImpl(RechargeIntentService intents) { this.intents = intents; }
    @Override public SourceFact resolve(EntitlementContext ctx, SourceRef source) {
        var plan = intents.find(ctx.tenantNo(), source.sourceNo()); intents.requireSource(plan, source);
        return plan.fact();
    }
    @Override public SourceFact byRechargeNo(EntitlementContext ctx, String no) {
        return intents.byRechargeNo(ctx.tenantNo(), no).fact();
    }
}
