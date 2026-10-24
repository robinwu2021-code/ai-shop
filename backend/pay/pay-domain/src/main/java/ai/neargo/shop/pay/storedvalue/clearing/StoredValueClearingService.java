package ai.neargo.shop.pay.storedvalue.clearing;

import ai.neargo.shop.spi.entitlement.EntitlementContext;

/** Stable PAY business contract; original owner checks and transaction boundaries remain in its implementation. */
public interface StoredValueClearingService {
    void record(EntitlementContext ctx,String usageNo,String voucherNo);
}
