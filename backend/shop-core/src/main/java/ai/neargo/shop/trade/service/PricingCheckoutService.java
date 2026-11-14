package ai.neargo.shop.trade.service;

import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Credentials;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;
import ai.neargo.shop.spi.trade.PricingSourceRef;
import ai.neargo.shop.spi.trade.PricingCheckoutPort.QuoteVO;
import ai.neargo.shop.spi.trade.PricingCheckoutPort.Selection;

/** Authorized current source -> shared formula/tax -> immutable quote; never reserves or consumes assets. */
public interface PricingCheckoutService {
    QuoteVO quoteStaff(Scope scope, String actorNo, PricingSourceRef source, Selection selection, String key);
    QuoteVO quoteCustomer(PricingSourceRef source, Credentials credentials, Selection selection, String key);
    QuoteVO getStaff(Scope scope, String actorNo, PricingSourceRef source, String quoteNo);
    QuoteVO getCustomer(PricingSourceRef source, Credentials credentials, String quoteNo);
    QuoteVO revalidateStaff(Scope scope, String actorNo, PricingSourceRef source, String quoteNo, String digest);
    QuoteVO revalidateCustomer(PricingSourceRef source, Credentials credentials, String quoteNo, String digest);
}
