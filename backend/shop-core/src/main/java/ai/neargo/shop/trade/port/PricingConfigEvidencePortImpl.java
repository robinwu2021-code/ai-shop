package ai.neargo.shop.trade.port;

import ai.neargo.shop.spi.trade.PricingConfigEvidencePort;
import ai.neargo.shop.trade.service.PricingConfigEvidenceService;
import org.springframework.stereotype.Component;

/** Explicit FOOD/CART unsupported-owner proof, never a default capability provider. */
@Component
public class PricingConfigEvidencePortImpl implements PricingConfigEvidencePort {
    private final PricingConfigEvidenceService service;
    public PricingConfigEvidencePortImpl(PricingConfigEvidenceService service) { this.service = service; }
    @Override public Evidence load(Scope scope) { return service.load(scope); }
}
