package ai.neargo.shop.pay.entitlement.service;

import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.OrderBenefitReverse;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.UseReceipt;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.ReversalView;

/** Internal compensation only; this is not an online refund API for stored value or paid cards. */
public interface EntitlementReversalService {
    UseReceipt reverse(EntitlementContext context, OrderBenefitReverse command);
    ReversalView queryReverse(EntitlementContext context, String entityNo, String reverseNo);
}
