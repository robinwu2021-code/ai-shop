package ai.neargo.shop.pay.migration.service;

import ai.neargo.shop.spi.entitlement.*;

import ai.neargo.shop.spi.entitlement.LegacyCouponHistoricalReturnPort.Receipt;

/** Domain contract; transactional behavior remains in the implementation. */
public interface LegacyCouponHistoricalReturnService {
    Receipt reverse(EntitlementContext ctx,String entity,String no);
    Receipt query(EntitlementContext ctx,String entity,String no);
}
