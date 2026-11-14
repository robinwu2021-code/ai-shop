package ai.neargo.shop.pay.storedvalue.service;

import ai.neargo.shop.spi.entitlement.DecisionRef;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.HoldView;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.UseCommand;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.spi.storedvalue.StoredValueUseQuotePort.Quote;

public interface StoredValueRedemptionService {
    Quote quote(EntitlementContext context, SourceRef source);
    Quote quote(EntitlementContext context,SourceRef source,ai.neargo.shop.spi.storedvalue.StoredValueWallets.Choice choice);
    HoldView prepare(EntitlementContext context, UseCommand command);
    HoldView finish(EntitlementContext context, String operationNo, SourceRef source, DecisionRef decision,
                    boolean commit);
}
