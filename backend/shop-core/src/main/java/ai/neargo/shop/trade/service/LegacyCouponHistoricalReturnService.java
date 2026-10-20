package ai.neargo.shop.trade.service;

import ai.neargo.shop.spi.entitlement.*;

import ai.neargo.shop.spi.entitlement.LegacyCouponHistoricalReturnSourcePort.Fact;

import java.time.*;

import java.util.*;

import org.springframework.transaction.annotation.*;

/** Domain contract; transactional behavior remains in the implementation. */
public interface LegacyCouponHistoricalReturnService {
    boolean cancelled(EntitlementContext ctx,String orderNo);
    List<Fact> refunded(EntitlementContext ctx,String afterSaleNo);
    Fact resolve(EntitlementContext ctx,String no);
    void acknowledge(EntitlementContext ctx,LegacyCouponHistoricalReturnPort.Receipt receipt);
    String returnedTitle(String tenant,String orderNo);
}
