package ai.neargo.shop.spi.trade;

import ai.neargo.shop.spi.trade.PricingCheckoutPort.QuoteVO;
import ai.neargo.shop.spi.trade.PricingCheckoutPort.Selection;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Credentials;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Authenticated owner transport. Original source credentials remain mandatory for every consumer read. */
public interface PricingCheckoutInternalApi {
    String ROOT = "/internal/trade/pricing-checkout";
    @PostExchange(ROOT + "/staff/quote") QuoteVO quoteStaff(@RequestBody StaffQuote request);
    @PostExchange(ROOT + "/customer/quote") QuoteVO quoteCustomer(@RequestBody CustomerQuote request);
    @PostExchange(ROOT + "/staff/get") QuoteVO getStaff(@RequestBody StaffRead request);
    @PostExchange(ROOT + "/customer/get") QuoteVO getCustomer(@RequestBody CustomerRead request);
    @PostExchange(ROOT + "/staff/revalidate") QuoteVO revalidateStaff(@RequestBody StaffRead request);
    @PostExchange(ROOT + "/customer/revalidate") QuoteVO revalidateCustomer(@RequestBody CustomerRead request);

    record StaffQuote(Scope scope, String actorNo, PricingSourceRef source, Selection selection, String key) { }
    record CustomerQuote(PricingSourceRef source, Credentials credentials, Selection selection, String key) {
        @Override public String toString() { return "CustomerQuote[REDACTED]"; }
    }
    record StaffRead(Scope scope, String actorNo, PricingSourceRef source, String quoteNo, String digest) { }
    record CustomerRead(PricingSourceRef source, Credentials credentials, String quoteNo, String digest) {
        @Override public String toString() { return "CustomerRead[REDACTED]"; }
    }
}
