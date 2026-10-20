package ai.neargo.shop.pay.storedvalue.port;

import ai.neargo.shop.pay.storedvalue.service.StoredValueRedemptionService;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.spi.storedvalue.StoredValueUseQuotePort;

/** Bound only by the deployment composition root, just like grant/use owner adapters. */
public class StoredValueUseQuotePortImpl implements StoredValueUseQuotePort {
    private final StoredValueRedemptionService service;
    public StoredValueUseQuotePortImpl(StoredValueRedemptionService service) { this.service = service; }
    @Override public Quote quote(EntitlementContext ctx, SourceRef source) { return service.quote(ctx, source); }
}
