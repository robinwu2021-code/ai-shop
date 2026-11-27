package ai.neargo.shop.pay.storedvalue.clearing;

import ai.neargo.shop.spi.entitlement.EntitlementContext;
import java.time.LocalDateTime;

/** Stable PAY business contract; original owner checks and transaction boundaries remain in its implementation. */
public interface StoredValueClearingService {
    void record(EntitlementContext ctx,String usageNo,String voucherNo);

    void reverse(EntitlementContext ctx, String usageNo, String decisionNo, LocalDateTime occurredAt);
}
