package ai.neargo.shop.trade.service;

import ai.neargo.shop.spi.trade.PricingFundingCapabilities.Family;
import ai.neargo.shop.spi.trade.PricingFundingCapabilities.Scope;
import ai.neargo.shop.spi.trade.PricingFundingCapabilities.Snapshot;

/** Internal checks only; callers must still authorize stores, resolve funds and obtain owner quotes. */
public interface PricingFundingCapabilityService {
    Snapshot inspect(Family family, Scope scope);
    Snapshot require(Family family, Scope scope);
    Snapshot confirm(Family family, Scope scope, String expectedSnapshotHash);
}
