package ai.neargo.shop.food.spi;

import ai.neargo.shop.food.dto.CartAccess;
import ai.neargo.shop.food.dto.DiningQuoteReq;
import ai.neargo.shop.food.dto.DiningQuoteConfirmReq;
import ai.neargo.shop.spi.trade.PricingCheckoutPort.QuoteVO;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Restaurant-owned gateway transport. Shared trade owns monetary calculation. */
public interface DiningPricingInternalApi {
    String ROOT = "/internal/food/quotes";
    @PostExchange(ROOT + "/staff/quote") QuoteVO staffQuote(@RequestBody StaffQuote command);
    @PostExchange(ROOT + "/staff/get") QuoteVO staffGet(@RequestBody StaffGet command);
    @PostExchange(ROOT + "/staff/confirm") QuoteVO staffConfirm(@RequestBody StaffConfirm command);
    @PostExchange(ROOT + "/customer/quote") QuoteVO customerQuote(@RequestBody CustomerQuote command);
    @PostExchange(ROOT + "/customer/get") QuoteVO customerGet(@RequestBody CustomerGet command);
    @PostExchange(ROOT + "/customer/confirm") QuoteVO customerConfirm(@RequestBody CustomerConfirm command);

    record StaffQuote(String entity, String store, String actor, DiningQuoteReq request, String key) { }
    record StaffGet(String entity, String store, String actor, String cart, long version, String quote) { }
    record StaffConfirm(String entity, String store, String actor, String quote, DiningQuoteConfirmReq request) { }
    record CustomerQuote(String store, CartAccess access, DiningQuoteReq request, String key) {
        @Override public String toString() { return "CustomerQuote[REDACTED]"; }
    }
    record CustomerGet(CartAccess access, String cart, long version, String quote) {
        @Override public String toString() { return "CustomerGet[REDACTED]"; }
    }
    record CustomerConfirm(CartAccess access, String quote, DiningQuoteConfirmReq request) {
        @Override public String toString() { return "CustomerConfirm[REDACTED]"; }
    }
}
