package ai.neargo.shop.trade.service;

import ai.neargo.shop.spi.trade.PricingConfigEvidencePort.Evidence;
import ai.neargo.shop.spi.trade.PricingConfigEvidencePort.Scope;

/** Publication limits inferred only from current real owner descriptors for an exact industry/store. */
public interface PricingConfigEvidenceService {
    Evidence load(Scope scope);
}
