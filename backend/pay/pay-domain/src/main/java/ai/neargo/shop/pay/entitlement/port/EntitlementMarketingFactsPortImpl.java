package ai.neargo.shop.pay.entitlement.port;

import ai.neargo.shop.pay.entitlement.service.EntitlementMarketingFactsService;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementMarketingFactsPort;

/** Bound by deployment composition, keeping core callers away from pay mappers and entities. */
public class EntitlementMarketingFactsPortImpl implements EntitlementMarketingFactsPort {
    private final EntitlementMarketingFactsService service;
    public EntitlementMarketingFactsPortImpl(EntitlementMarketingFactsService service) { this.service=service; }
    @Override public UsageEffects usage(EntitlementContext ctx,String entity,String no,String after,int limit) {
        return service.usage(ctx,entity,no,after,limit);
    }
}
