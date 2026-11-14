package ai.neargo.shop.trade.service;

import ai.neargo.shop.spi.trade.PricingTaxCalculationPort.Calculation;
import ai.neargo.shop.spi.trade.PricingTaxCalculationPort.Input;
import ai.neargo.shop.spi.trade.PricingTaxSettingPort.Scope;

/** Shared calculation orchestration; order/quote persistence remains with their own services. */
public interface PricingTaxCalculationService {
    Calculation estimate(Scope scope, Input input);
    Calculation confirm(Scope scope, Input current, Calculation expected);
}
