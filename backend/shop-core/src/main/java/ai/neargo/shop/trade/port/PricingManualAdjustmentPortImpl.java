package ai.neargo.shop.trade.port;

import ai.neargo.shop.spi.trade.PricingCheckoutPort.QuoteVO;
import ai.neargo.shop.spi.trade.PricingManualAdjustmentPort;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;
import ai.neargo.shop.trade.service.PricingManualAdjustmentService;
import org.springframework.stereotype.Component;

/** Cross-domain manual adjustments retain the existing seller authorization and quote authority. */
@Component
public class PricingManualAdjustmentPortImpl implements PricingManualAdjustmentPort {
    private final PricingManualAdjustmentService service;

    public PricingManualAdjustmentPortImpl(PricingManualAdjustmentService service) { this.service = service; }

    @Override public AdjustmentVO propose(Scope scope, String actorNo, Request request, String key) {
        return service.propose(scope, actorNo, request, key);
    }
    @Override public AdjustmentVO get(Scope scope, String actorNo, String adjustmentNo) {
        return service.get(scope, actorNo, adjustmentNo);
    }
    @Override public AdjustmentVO approve(Scope scope, String actorNo, String adjustmentNo, long version, String key) {
        return service.approve(scope, actorNo, adjustmentNo, version, key);
    }
    @Override public QuoteVO quote(Scope scope, String actorNo, String adjustmentNo, String key) {
        return service.quote(scope, actorNo, adjustmentNo, key);
    }
}
