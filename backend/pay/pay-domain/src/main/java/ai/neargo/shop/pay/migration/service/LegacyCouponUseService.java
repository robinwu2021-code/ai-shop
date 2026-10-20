package ai.neargo.shop.pay.migration.service;

import ai.neargo.shop.spi.entitlement.EntitlementContext;

import ai.neargo.shop.spi.entitlement.LegacyCouponUsePort.*;

/** Domain contract; transactional behavior remains in the implementation. */
public interface LegacyCouponUseService {
    Snapshot snapshot(EntitlementContext ctx,Owned command);
    Estimate estimate(EntitlementContext ctx,EstimateCommand command);
    Quote quote(EntitlementContext ctx,Prepare command);
    Operation prepare(EntitlementContext ctx,Prepare command);
    Operation commit(EntitlementContext ctx,Complete command);
    Operation release(EntitlementContext ctx,Complete command);
    Operation reverse(EntitlementContext ctx,Complete command);
    Operation query(EntitlementContext ctx,String entity,String operation);
}
