package ai.neargo.shop.trade.service;

import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;
import ai.neargo.shop.trade.dto.PricingPreparation.RuntimeConfig;

/** Read an active publication for an already authorized server scope; never fall back to its working draft. */
public interface PricingRuntimeConfigService {
    RuntimeConfig resolve(Scope scope, String industry);
}
