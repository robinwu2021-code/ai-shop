package ai.neargo.shop.trade.port;

import ai.neargo.shop.spi.trade.PricingTaxCalculationPort;
import ai.neargo.shop.spi.trade.PricingTaxSettingPort.Scope;
import ai.neargo.shop.trade.service.PricingTaxCalculationService;
import org.springframework.stereotype.Component;

/** Food, retail and beauty access shared tax calculation through this single trade-owned port. */
@Component
public class PricingTaxCalculationPortImpl implements PricingTaxCalculationPort {
    private final PricingTaxCalculationService service;

    public PricingTaxCalculationPortImpl(PricingTaxCalculationService service) { this.service = service; }

    @Override
    public Calculation estimate(Scope scope, Input input) { return service.estimate(scope, input); }

    @Override
    public Calculation confirm(Scope scope, Input current, Calculation expected) {
        return service.confirm(scope, current, expected);
    }
}
