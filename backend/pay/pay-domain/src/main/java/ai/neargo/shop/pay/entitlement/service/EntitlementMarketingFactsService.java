package ai.neargo.shop.pay.entitlement.service;

import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementMarketingFactsPort.UsageEffects;

/** Reads committed pay-owned facts without acquiring financial write locks. */
public interface EntitlementMarketingFactsService {
    UsageEffects usage(EntitlementContext context,String entityNo,String usageNo,String afterActivityNo,int limit);
}
