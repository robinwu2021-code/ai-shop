package ai.neargo.shop.trade.port;

import ai.neargo.shop.spi.trade.PricingCheckoutPort;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Credentials;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;
import ai.neargo.shop.spi.trade.PricingSourceRef;
import ai.neargo.shop.trade.service.PricingCheckoutService;
import org.springframework.stereotype.Component;

/** The same typed core boundary is used by the host and the standalone industry transport. */
@Component
public class PricingCheckoutPortImpl implements PricingCheckoutPort {
    private final PricingCheckoutService service;
    public PricingCheckoutPortImpl(PricingCheckoutService service) { this.service = service; }
    @Override public QuoteVO quoteStaff(Scope scope, String actor, PricingSourceRef source, Selection selection, String key) {
        return service.quoteStaff(scope, actor, source, selection, key);
    }
    @Override public QuoteVO quoteCustomer(PricingSourceRef source, Credentials credentials, Selection selection, String key) {
        return service.quoteCustomer(source, credentials, selection, key);
    }
    @Override public QuoteVO getStaff(Scope scope, String actor, PricingSourceRef source, String no) {
        return service.getStaff(scope, actor, source, no);
    }
    @Override public QuoteVO getCustomer(PricingSourceRef source, Credentials credentials, String no) {
        return service.getCustomer(source, credentials, no);
    }
    @Override public QuoteVO revalidateStaff(Scope scope, String actor, PricingSourceRef source, String no, String digest) {
        return service.revalidateStaff(scope, actor, source, no, digest);
    }
    @Override public QuoteVO revalidateCustomer(PricingSourceRef source, Credentials credentials, String no, String digest) {
        return service.revalidateCustomer(source, credentials, no, digest);
    }
}
