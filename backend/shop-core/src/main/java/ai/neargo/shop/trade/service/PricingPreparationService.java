package ai.neargo.shop.trade.service;

import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Credentials;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;
import ai.neargo.shop.spi.trade.PricingSourceRef;
import ai.neargo.shop.trade.dto.PricingPreparation.Prepared;

/** Re-authorizes and resolves current prerequisites; never returns a payable or persisted quote. */
public interface PricingPreparationService {
    Prepared staff(Scope scope, PricingSourceRef source);
    Prepared customer(PricingSourceRef source, Credentials credentials);
    Prepared confirmStaff(Scope scope, PricingSourceRef source, String expectedPreparationHash);
    Prepared confirmCustomer(PricingSourceRef source, Credentials credentials, String expectedPreparationHash);
}
