package ai.neargo.shop.trade.service;

import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Credentials;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Snapshot;
import ai.neargo.shop.spi.trade.PricingSourceRef;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Command;

/** Core source routing and fresh authorization; deliberately separate from final quote/asset admission. */
public interface PricingSourceService {
    Snapshot staff(Scope scope, PricingSourceRef source);
    Snapshot cancellationForStore(Command command, String key, String fulfillment);
    Snapshot customer(PricingSourceRef source, Credentials credentials);
    Snapshot confirmStaff(Scope scope, PricingSourceRef source, String expectedSnapshotHash);
    Snapshot confirmCustomer(PricingSourceRef source, Credentials credentials, String expectedSnapshotHash);
}
